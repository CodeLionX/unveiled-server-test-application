package sas.systems.unveiled.server.testapp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.rtsp.RtspHeaders;
import io.netty.handler.codec.rtsp.RtspMethods;
import io.netty.handler.codec.rtsp.RtspVersions;
import sas.systems.imflux.participant.RtpParticipant;
import sas.systems.imflux.participant.RtspParticipant;
import sas.systems.imflux.session.rtsp.RtspResponseListener;
import sas.systems.imflux.session.rtsp.SimpleRtspSession;

/**
 * Hello world!
 *
 */
public class ClientStarter implements RtspResponseListener {
	
	// configuration properties ---------------------------------------------------------------------------------------
	private static final String HOST = "localhost";
	private static final int RTP_PORT = 50000;
	private static final int RTCP_PORT = 50001;
	private static final int RTSP_PORT = 1935;
	private static final String REMOTE_HOST = "192.168.1.102";
	private static final int REMOTE_RTSP_PORT = 554;
	
	private static final String ID = "unveiled-test-app:0";
	
	public static final int CRLF  = 0x0D0A;
	
	private static enum RtspMethodSeq {
		INVALID(-1, "INVALID"),
		OPTIONS(0, "OPTIONS"),
		ANNOUNCE(1, "ANNOUNCE"),
		ANNOUNCE2(2, "ANNOUNCE"),
		SETUP(3, "SETUP"),
		RECORD(4, "RECORD"),
		TEARDOWN(5, "TEARDOWN");
		
		private final int cseq;
		private final String name;
		
		public static RtspMethodSeq fromInt(int cseq) {
			if(cseq == OPTIONS.cSeq())
				return OPTIONS;
			if(cseq == ANNOUNCE.cSeq())
				return ANNOUNCE;
			if(cseq == SETUP.cSeq())
				return SETUP;
			if(cseq == RECORD.cSeq())
				return RECORD;
			if(cseq == TEARDOWN.cSeq())
				return TEARDOWN;
			
			return INVALID;
		}
		
		public static List<String> allNames() {
			List<String> names = new ArrayList<>();
			names.add(OPTIONS.name);
			names.add(ANNOUNCE.name);
			names.add(SETUP.name);
			names.add(RECORD.name);
			names.add(TEARDOWN.name);
			
			return names;
		}
		
		private RtspMethodSeq(int cseq, String name) {
			this.cseq = cseq;
			this.name = name;
		}
		
		public int cSeq() {
			return this.cseq;
		}
	}
	
	private final String baseURI;
	private final SimpleRtspSession session;
	private final AtomicInteger cSeqCounter;
	private final SocketAddress remoteAddress;
	private final AtomicBoolean receivedResponse;
	private final AtomicBoolean validResponse;
	
	private String sessionId = null;
	
	public ClientStarter() {
		System.out.println("\tSetting up local session with ID " + ID + ":");
		System.out.println("\tRTP:\t" + HOST + ":" + RTP_PORT);
		System.out.println("\tRTCP\t" + HOST + ":" + RTCP_PORT);
		System.out.println("\tRTSP:\t" + HOST + ":" + RTSP_PORT);
		final RtpParticipant localRtpParticipant = RtpParticipant.createReceiver(HOST, RTP_PORT, RTCP_PORT);
        final SocketAddress localAddress = new InetSocketAddress(HOST, RTSP_PORT);
        this.session = new SimpleRtspSession(ID, localRtpParticipant, localAddress);
        
        this.baseURI = "rtsp://sas.systemgrid.de/";
        this.cSeqCounter = new AtomicInteger(1);
        this.receivedResponse = new AtomicBoolean(false);
        this.validResponse = new AtomicBoolean(false);
        
        System.out.println("\n\tUsing following remote connection:");
        System.out.println("\tremote RTCP:\t" + REMOTE_HOST + ":" + REMOTE_RTSP_PORT);
        this.remoteAddress = new InetSocketAddress(REMOTE_HOST, REMOTE_RTSP_PORT);
	}
	
    public static void main(String[] args ) {
        System.out.println("Starting application and session...." );
        final ClientStarter client = new ClientStarter();
        if(!client.startup()) {
        	System.err.println("Error during session startup!!");
        }
        System.out.println("... finished.\n");
        
        System.out.println("Sending OPTIONS request");
        if(!client.sendOptionsRequest()) {
        	System.err.println("No connection, no response or error....closing application!");
        	client.shutdown();
        	return;
        }
        
        System.out.println("Sending ANNOUNCE");
        if(!client.sendAnnounceRequest(true)) {
        	System.err.println("No connection, no response or error....retrying with login information!");
        }
        
        System.out.println("Sending ANNOUNCE with login information");
        if(!client.sendAnnounceRequest(true)) {
        	System.err.println("No connection, no response or error....closing application!");
        	client.shutdown();
        	return;
        }
        
        System.out.println("Received session id: " + client.sessionId);
        
        System.out.println("Closing connection");
        client.shutdown();
    }
    
	public synchronized boolean startup() {
    	this.session.setUseNio(true);
    	this.session.setAutomatedRtspHandling(false);
    	this.session.addResponseListener(this);
    	return this.session.init();
    }
    
    public synchronized void shutdown() {
    	this.session.terminate();
    }
    
    public boolean sendOptionsRequest() {
    	final HttpRequest request = new DefaultHttpRequest(RtspVersions.RTSP_1_0, RtspMethods.OPTIONS, this.baseURI);
    	request.headers().add(RtspHeaders.Names.CSEQ, RtspMethodSeq.OPTIONS.cSeq());
    	request.headers().add(RtspHeaders.Names.CONTENT_LENGTH, 0);
    	// session n/a
    	// authorization n/a
		final boolean wasSend = this.session.sendRequest(request, remoteAddress);
		
		// wait for response
		return wasSend && waitForResponse();
	}
    
    public boolean sendAnnounceRequest(boolean withAuthorization) {
    	final ByteBuf content = Unpooled.buffer();
    	writeLine(content, "v=0");					// version
    	writeLine(content, "o=- 0 0 IN IP4 null");	// originator
    	writeLine(content, "s=TestApplication");	// name
    	writeLine(content, "i=N/A");				// session info
    	writeLine(content, "c=IN IP4 127.0.0.1");	// connection info
    	writeLine(content, "t=0 0");				// active session time
    	writeLine(content, "a=recvonly");			// session attributes
    	writeLine(content, "m=video 5006 RTP/AVP 96"); // media info
    	writeLine(content, "a=rtpmap:96 H264/90000"); // media attributes
    	writeLine(content, "a=fmtp:96 packetization-mode=1;profile-level-id=420014;sprop-parameter-sets=J0IAFKaCxMQ=,KM48gA==;");
    	writeLine(content, "a=control:trackID=1");
    	
    	HttpRequest request = new DefaultFullHttpRequest(RtspVersions.RTSP_1_0, RtspMethods.ANNOUNCE, this.baseURI + "teststream", content);
    	request.headers().add(RtspHeaders.Names.CSEQ, RtspMethodSeq.ANNOUNCE.cSeq());
    	request.headers().add(RtspHeaders.Names.CONTENT_TYPE, "application/sdp");
    	request.headers().add(RtspHeaders.Names.CONTENT_LENGTH, content.readableBytes());
    	if(withAuthorization)
    		request.headers().add(RtspHeaders.Names.AUTHORIZATION, "Digest username=\"2\", nonce=\"token\"");
    	// session n/a
		boolean wasSend = this.session.sendRequest(request, remoteAddress);
		
		// wait for response
		return wasSend && waitForResponse();
    }
    
    public boolean sendSetupRequest() {
    	final HttpRequest request = new DefaultHttpRequest(RtspVersions.RTSP_1_0, RtspMethods.SETUP, this.baseURI);
    	request.headers().add(RtspHeaders.Names.CSEQ, RtspMethodSeq.SETUP.cSeq());
    	request.headers().add(RtspHeaders.Names.CONTENT_LENGTH, 0);
    	request.headers().add(RtspHeaders.Names.SESSION, this.sessionId);
    	request.headers().add(RtspHeaders.Names.AUTHORIZATION, "Digest username=\"2\", nonce=\"token\"");
    	
		final boolean wasSend = this.session.sendRequest(request, remoteAddress);
		
		// wait for response
		return wasSend && waitForResponse();
	}
    
    private void writeLine(ByteBuf buf, String text) {
    	buf.writeBytes(text.getBytes(Charset.forName("UTF-8")));
    	buf.writeInt(CRLF);
    }

	@Override
	public void responseReceived(HttpResponse message, RtspParticipant participant) {
		this.receivedResponse.set(true);
		int cseq = -1;
		
		try {
			cseq = Integer.valueOf(message.headers().get(RtspHeaders.Names.CSEQ));
		} catch(NumberFormatException e) {
			System.err.println("Response with missing or wrong sequence number!");
		}
		
//		if(this.cSeqCounter.compareAndSet(cseq, cseq+1)) {
//			System.out.println("Got message with wrong sequence number: " + cseq);
//			// leave method if wrong sequence number
//			this.validResponse.set(false);
//			synchronized (this) {
//				this.notifyAll();
//			}
//			return;
//		}
		System.out.println("Response received: ");
		System.out.println("\t" + message.toString().replaceAll("\r\n", "\n\t"));
		if(message.getStatus().code() != 200) {
			System.err.println("Received error response: " + message.getStatus().reasonPhrase());
			this.validResponse.set(false);
			synchronized (this) {
				this.notifyAll();
			}
			return;
		}
		
		switch (RtspMethodSeq.fromInt(cseq)) {
		case OPTIONS:
			final String optionsString = message.headers().get(RtspHeaders.Names.PUBLIC);
			if(optionsString != null) {
				final List<String> options = Arrays.asList(optionsString.split(", "));
				if(!options.containsAll(RtspMethodSeq.allNames())) {
					System.err.println("Response does not contain all needed PUBLIC OPTIONS!");
				}
			}
			
			break;
		case ANNOUNCE:
			final String sessionString = message.headers().get(RtspHeaders.Names.SESSION);
			if(sessionString != null) {
				// cut session id ...rest is: ";timeout=xx"
				final String sessionId = sessionString.substring(0, sessionString.lastIndexOf(";"));
				this.sessionId = sessionId;
			}
			break;
		case SETUP:
			// extract server ports
			final String transport = message.headers().get(RtspHeaders.Names.TRANSPORT);
			final int lastIndexEq = transport.lastIndexOf('=');
			final String server_ports = transport.substring(lastIndexEq+1, transport.length());
			final int indexMin = server_ports.indexOf('-');
			int data = Integer.valueOf(server_ports.substring(0, indexMin));
			int control = Integer.valueOf(server_ports.substring(indexMin+1, server_ports.length()));
			System.out.println("Server data port: " + data + "\nServer control port: " + control);
			
			break;
		case RECORD:

			break;
		case TEARDOWN:

			break;	

		case INVALID:
		default:
			// leave method when wrong response
			this.validResponse.set(false);
			synchronized (this) {
				this.notifyAll();
			}
			return;
		}
		
		this.validResponse.set(true);
		synchronized (this) {
			this.notifyAll();
		}
	}
	

    private boolean waitForResponse() {
    	this.validResponse.set(false);
    	// acquire lock and wait for response
    	synchronized (this) {
			while(!receivedResponse.get()) {
				try {
					this.wait();
				} catch (InterruptedException e) {
					System.err.println("Thread was interrupted!");
					shutdown();
					return false;
				}
			}
			receivedResponse.set(false);
		}
		return this.validResponse.get();
    }
}
