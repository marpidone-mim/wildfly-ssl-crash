package ssl.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;

public class Server {
	static final int PORT = 17761;
	
	static final ExecutorService SOCKET_WORKER_POOL = Executors.newFixedThreadPool(100);
	
	static final AtomicBoolean shutDown = new AtomicBoolean(false);
	
	static SSLContext sslContext;
	static volatile ServerSocket serverSock;
	
	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("No lifespan provided");
			System.exit(30);
			return;
		}
		
		long lifespan;
		
		try {
			lifespan = Long.valueOf(args[0]);
		} catch (NumberFormatException e) {
			System.err.println("Bad lifespan: " + args[0]);
			System.exit(31);
			return;
		}
		
		try {
			sslContext = App.initSsl();
			startServerControlThread(lifespan);
			runServerBlocking(lifespan);
			System.out.println("Server reached end of main");
			System.exit(42);
		} catch (Throwable t) {
			System.err.println("Unexpected exception in main");
			t.printStackTrace();
			System.exit(33);
		}
	}
	
	private static void startServerControlThread(long lifespan) {
		Thread mainServerThread = Thread.currentThread();
		
		Thread controlThread = new Thread(()->{
			try {
				System.out.println("Control thread start; sleeping");
				
				Thread.sleep(lifespan);
				
				System.out.println("Control thread setting shutdown flag");
				shutDown.set(true);
				
				mainServerThread.join();
				
				System.out.println("Control thread killing socket");
				ServerSocket sockCap = serverSock;
				if (sockCap != null) {
					sockCap.close();
				}
				
				Thread.sleep(5000);
				
				System.out.println("No clean shutdown?");
				System.exit(35);
			} catch (Throwable t) {
				System.err.println("Control thread boom; killing");
				t.printStackTrace();
				System.exit(34);
			}
		}, "Server Control Thread");
		
		controlThread.setDaemon(true);
		controlThread.start();
	}
	
	public static void runServerBlocking(long lifespan) throws Exception {
		serverSock = sslContext.getServerSocketFactory().createServerSocket(PORT);
		serverSock.setSoTimeout(500);
		
		while (!shutDown.get()) {
			try {
				handleNextConnection(serverSock);
			} catch (SocketTimeoutException ex) {
				
			} catch (Throwable t) {
				System.err.println("Connection handle boom");
				t.printStackTrace();
			}
		}
	}
	
	public static void handleNextConnection(ServerSocket sss) throws IOException {
		Socket sock = sss.accept();
		
		// Uncomment this line and the crashes don't happen
//		((SSLSocket)sock).getHandshakeSession();
		
		SOCKET_WORKER_POOL.submit(()->{
			try {
				InputStream in = sock.getInputStream();
				OutputStream out = sock.getOutputStream();
				int message = in.read();
				if (message != 1) {
					System.err.println("Unexpected client message: " + message);
				}
				out.write(1);
				out.flush();
			} catch (Throwable t) {
				System.err.println("Server data outer bomb");
				t.printStackTrace();
			} finally {
				try {
					sock.close();
				} catch (Throwable t) {
					System.err.println("Socket close bomb");
					t.printStackTrace();
				}
			}
		});
	}
}
