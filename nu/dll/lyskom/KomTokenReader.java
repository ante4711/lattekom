/**! -*- Mode: Java; c-basic-offset: 4 -*-
 *
 * Copyright (c) 1999 by Rasmus Sten <rasmus@sno.pp.se>
 *
 */
// -*- Mode: Java; c-basic-offset: 4 -*-
package nu.dll.lyskom;

import java.io.*;
import java.net.ProtocolException;
import java.util.Vector;
import java.util.Enumeration;

public class KomTokenReader {

    final static int DEBUG = 3;

    private InputStream input;

    boolean lastByteWasEol = false;

    KomToken lastToken = null;

    public KomTokenReader(InputStream i) {
	input = i;
    }

    public KomTokenArray readArray()
    throws IOException {
	if (lastToken == null) {
	    throw new KomProtocolException("KomTokenReader.readArray(): " +
					   "lastToken was null");
	}
	int length = lastToken.toInteger();
	Vector v = new Vector();
        KomToken last = readToken();
	String lspre = new String(last.toNetwork());
	while (!lspre.equals("}")) {
	    if (lspre.equals("{"))
		v.addElement((Object) readArray());
	    else		      
		v.addElement((Object) last);

	    last = readToken(); lspre = new String(last.toNetwork());
	}
	KomToken[] arr = new KomToken[v.size()];
	Enumeration e = v.elements();
	for (int i=0;i<v.size();i++) {
	    arr[i] = (KomToken) e.nextElement();	    
	}
	if (DEBUG>1) Debug.println("Array end ("+arr.length+")");
	return new KomTokenArray(length, arr);
    }

    StringBuffer debugBuffer;
    public KomToken[] readLine()
    throws ProtocolException, IOException {
	debugBuffer = new StringBuffer();
	Vector v = new Vector();
	while (!lastByteWasEol)
	    v.addElement((Object) readToken());

	lastByteWasEol = false;

	KomToken[] foo = new KomToken[v.size()];

	Enumeration e = v.elements();
	for (int i=0;i<foo.length;i++)
	    foo[i] = (KomToken) e.nextElement();
	if (DEBUG > 2)
	    Debug.println("RCV: " + debugBuffer.toString());
	return foo;
    }

    // Ultimately, we should fix our own InputSteamReader
    private void read(InputStream in, byte[] buffer)
    throws IOException {
	for (int i=0;i<buffer.length;i++) {
	    byte b = (byte) in.read();
	    if (b == -1) throw(new IOException("End of Stream"));
	    buffer[i] = b;
	}
	if (debugBuffer != null) debugBuffer.append(new String(buffer) + " ");
    }

    public KomToken readToken()
    throws IOException, ProtocolException {
	StringBuffer buff = new StringBuffer();
	boolean readMore = true;
	int arrlen = -1;
	byte b = 0;
	byte lastB = 0;

	while (true) {
	    lastB = b;
	    b = (byte) input.read();
	    if (debugBuffer != null) debugBuffer.append((char) b);
	    //Debug.println(new String((char) b));
	    if (b == '\n')
		lastByteWasEol = true;
	    else
		lastByteWasEol = false;
	    
	    switch ((int)b) {
	    case -1:
		throw(new IOException("End of stream"));
	    case '\n':
	    case ' ':
		if (lastB == b)
		    break;

		return lastToken = new KomToken(buff.toString().getBytes());
	    case '*':
		arrlen = (lastToken != null ? lastToken.toInteger() : -1);
		return (KomToken) new KomTokenArray(arrlen);
	    case '{':
		input.read(); // eat up leading space
		return lastToken = (KomToken) readArray();
	    case 'H':
		try {
		    arrlen = Integer.parseInt(buff.toString());
		} catch (NumberFormatException x) {
		    throw(new KomProtocolException("Bad hollerith \""+
						   buff.toString() + "\"?"));
		}
		byte[] hstring = new byte[arrlen];
		read(input, hstring);
		if (DEBUG>2)
		    Debug.println("readToken(): hollerith: \""+
				       new String(hstring) + "\"");
		if (input.read() == '\n') { // eat trailing space/cr
		    if (DEBUG>2) Debug.println("EOL");
		    lastByteWasEol = true;
		} else {
		    lastByteWasEol = false;
		}
		return lastToken = (KomToken) new Hollerith(hstring);
	    default:
		buff.append((char) b);
	    }
	}
    }
}


