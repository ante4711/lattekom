/**! -*- Mode: Java; c-basic-offset: 4 -*-
 *
 * Copyright (c) 1999 by Rasmus Sten <rasmus@sno.pp.se>
 *
 */
// -*- Mode: Java; c-basic-offset: 4 -*-
package nu.dll.lyskom;

import java.net.*;
import java.io.*;


public class Connection {

    private Socket sock;
    private InputStream input;
    private OutputStream output;
    String server;
    int port;

    public Connection(String server, int port)
    throws IOException, UnknownHostException {
	this.server = server;
	this.port = port;

	sock = new Socket(server, port);
	input = sock.getInputStream();
	output = sock.getOutputStream();
	
    }

    public String getServer() {
	return server;
    }

    public int getPort() {
	return port;
    }
    
    public void close()
    throws IOException {
	sock.close();
    }

    public InputStream getInputStream() {
	return input;
    }

    public OutputStream getOutputStream() {
	return output;
    }
    
    public void write(char c)
    throws IOException {
	synchronized (output) {
	    output.write(c);
	}
    }

    public void writeLine(byte[] b) 
    throws IOException {
	synchronized (output) {
	    output.write(b);
	    output.write('\n');
	}
    }

    public void writeLine(String s)
    throws IOException {
	synchronized (output) {
	    output.write(s.getBytes());
	    output.write('\n');
	}
    }

    /* appending to a StringBuffer doesnt feel very efficient.
     * Maybe we should use an array buffer instead (which also makes
     * it easier to take character encoding into account when converting
     * to string?).
     */

    public String readLine(String s) 
    throws IOException {
	StringBuffer buff = new StringBuffer();
	byte b = (byte) input.read();
	while (b != -1 && b != '\n') {	    
	    buff.append((char) b);
	    b = (byte) input.read();
	}

	switch (b) {
	case -1:
	    Debug.println("Connection.readLine(): EOF from stream");
	case 0:
	    Debug.println("Connection.readLine(): \\0 from stream");
	}
	
	return buff.toString();
    }

	
    
}