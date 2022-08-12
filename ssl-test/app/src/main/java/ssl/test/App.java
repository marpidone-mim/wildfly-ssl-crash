package ssl.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.wildfly.openssl.OpenSSLProvider;

public class App {
	/** The number of concurrent client threads to start up during each round */
	private static final int NUM_CLIENTS = 10;
	private static final ExecutorService CLIENT_EXECUTOR = Executors.newFixedThreadPool(NUM_CLIENTS);
	
	public static SSLContext initSsl() throws Exception {
		System.setProperty("org.wildfly.openssl.path", "C:\\Program Files\\OpenSSL-Win64\\bin");
		OpenSSLProvider.register();
		SSLContext sslContext = SSLContext.getInstance("openssl.TLS");
		
		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (var in = new FileInputStream("./ssl-test.keystore")) {
			ks.load(in, "ssl-test".toCharArray());
		}
		
		KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		km.init(ks, "ssl-test".toCharArray());
		
		TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		factory.init(ks);
		
		TrustManager[] trustManagers = factory.getTrustManagers();
		
		sslContext.init(km.getKeyManagers(), trustManagers, null);
		
		return sslContext;
	}
	
	public static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
	
	private static SSLContext sslContext;
	
	public static void main(String[] args) throws Exception {
		try {
			sslContext = initSsl();
			
			long serverLifespan = TimeUnit.SECONDS.toMillis(5);
			int attempts = 10;
			boolean crashed = false;
			
			for (int i = 1; i <= attempts && !crashed; i++) {
				System.out.printf("Round %d: Launching server\n", i);
				var abortLatch = new CountDownLatch(1);
				var serverFuture = startServer(serverLifespan, i);
				
				sleep(500); // give the server some time to start up
				
				var clientFuture = startClients(abortLatch);
				
				serverFuture.thenRun(() -> abortLatch.countDown());
				
				int exitCode = serverFuture.join();
				clientFuture.join();
				
				crashed = exitCode == 1;
				System.out.printf("Server exited with code: %d%s\n", exitCode, crashed ? " CRASHED" : "");
			}
		} finally {
			CLIENT_EXECUTOR.shutdown();
		}
	}
	
	private static CompletableFuture<Integer> startServer(long lifeSpan, int round) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(new File(System.getProperty("java.home"), "bin/java.exe").getAbsolutePath());
		command.add("-Djava.awt.headless=true");
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(Server.class.getCanonicalName());
		command.add(String.valueOf(lifeSpan));
		
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.redirectErrorStream(true);
		pb.redirectOutput(new File(String.format("./server-%02d.txt", round)));
		
		Process proc = pb.start();
		
		return CompletableFuture.supplyAsync(() -> {
			try {
				return proc.waitFor();
			} catch (InterruptedException ex) {
				proc.destroyForcibly();
				return 2;
			}
		});
	}
	
	private static CompletableFuture<?> startClients(CountDownLatch abortLatch) {
		System.out.printf("Starting %d clients...\n", NUM_CLIENTS);
		
		var futures = new CompletableFuture<?>[NUM_CLIENTS];
		
		for (int i = 0; i < NUM_CLIENTS; i++) {
			futures[i] = CompletableFuture.runAsync(() -> {				
				while (abortLatch.getCount() > 0) {
					try {
						Socket sock = sslContext.getSocketFactory().createSocket("localhost", Server.PORT);
						InputStream in = sock.getInputStream();
						OutputStream out = sock.getOutputStream();
						out.write(1);
						out.flush();
						int response = in.read();
						if (response != 1) {
							System.out.println("Client got response: " + response);
						}
						sock.close();
					} catch (SocketException ex) {
						// Ignore, we don't care if or how the client connections fail, only whether the server crashed or not
						// Most of the time, these will be "Connection reset" or "Connection refused" because the server shutdown
						// while some of the client threads were still trying to connect or send a message.
					} catch (Throwable t) {
						t.printStackTrace();
					}
					
					try { Thread.sleep(1000); }
					catch (Throwable t) {}
				}
			}, CLIENT_EXECUTOR);
		}
		
		return CompletableFuture.allOf(futures);
	}
}
