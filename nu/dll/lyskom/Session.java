/**! -*- Mode: Java; c-basic-offset: 4 -*-
 *
 * Copyright (c) 1999 by Rasmus Sten <rasmus@sno.pp.se>
 *
 */

package nu.dll.lyskom;

import java.net.*;
import java.io.*;
import java.util.Enumeration;
import java.util.Vector;


/**
 * Session class. This handles connection, logging in, querying
 * unreads, creating texts, and a whole bunch of other stuff
 * @author rasmus@sno.pp.se
 * @version 0.1 */


public class Session
implements AsynchMessageReceiver, RpcReplyReceiver, RpcEventListener {
    public final static int MAX_TIMEOUT = 20000;
    public final static int DEFAULT_TIMEOUT = 5000;

    /**
     * Not connected to a LysKOM server
     */
    public final static int STATE_DISCONNECTED = 0;
    /**
     * Connected to a LysKOM server, but not logged in. In this state
     * only a few calls can be made to the server (lookupName,
     * login, create-person (not impl.), etc.) */
    public final static int STATE_CONNECTED = 1;
    /**
     * Connected, and logged in as a specific user
     */
    public final static int STATE_LOGIN = 2;

    int loginRpcId;
    boolean loggedIn = false;
    
    private static int rpcCount = 0;

    private int state = STATE_DISCONNECTED;

    Connection connection;
    KomTokenReader reader;
    MessageListener listener;

    String password;

    int wakeOnReplyFrom = -1;

    // Conferences in which we might have unread texts
    int[] unreads;
    Membership[] membership = new Membership[0];
    
    // Membership for the corresponding conferences
    Membership[] unreadMembership;
    // Vector for all unread texts. It'd be nice if we could provide a
    // TreeMap iterator to get all unread texts for each conference in
    // comment, depth-first order...
    TextMapping[] unreadTexts;
    
    int currentConference = -1;

    TextCache textCache;
    PersonCache personCache;
    ConferenceCache conferenceCache;
    MembershipCache membershipCache;
    TextStatCache textStatCache;
    
    ReadTextsMap readTexts;
    
    RpcHeap rpcHeap;

    Vector rpcEventListeners;

    Person myPerson = null;

    boolean connected = false;

    int lastText = 0;

    KomTokenArray[] emptyKomTokens = { };

    String clientHost = null;

    private void init() {
	textCache = new TextCache();
	personCache = new PersonCache();
	conferenceCache = new ConferenceCache();
	membershipCache = new MembershipCache();
	textStatCache = new TextStatCache();
	readTexts = new ReadTextsMap();
	rpcHeap = new RpcHeap();
	rpcEventListeners = new Vector(1);
	mainThread = Thread.currentThread();
    }

    Thread mainThread;
    public Session() {
	init();
    }

    public void setClientHost(String s) {
	clientHost = s;
    }
    

    /**
     * Adds and removes RPC reply listeners. These will be called
     * through the RpcEventListener (rpcEvent()) interface when
     * RPC replies are received.
     */
    public void addRpcEventListener(RpcEventListener l) {
	rpcEventListeners.addElement((Object) l);
    }
    public void removeRpcEventListener(RpcEventListener l) {
	rpcEventListeners.removeElement((Object) l);
    }

    /**
     * Traverses through the rpcEventListeners vector
     * and calls all registered event listeners.
     *
     * This method could probably be private.
     */    
    public void notifyRpcEventListeners(RpcEvent ev) {
 	for (Enumeration e = rpcEventListeners.elements();
	     e.hasMoreElements();)
	    ((RpcEventListener) e.nextElement()).rpcEvent(ev);
    }

    /**
     * Disconnects from the server
     *
     * @param force This parameter has no effect (currently)
     */
    public synchronized void disconnect(boolean force)
    throws IOException {
	
	// if we're not connected, then state could never be anything
	// but STATE_DISCONNECTED, right? So we can return without
	// explicitly setting state to that.
	if (!connected) return;
	// remove listeners/recievers
	removeRpcEventListener(this);
	listener.setAsynch(false);
	listener.removeAsynchMessageReceiver(this);
	listener.removeRpcReplyReceiver(this);
	connection.close();
	connection = null;
	connected = false;
	state = STATE_DISCONNECTED;
    }

    /**
     * Connect to specified server/port number and do initial handshake
     *
     * @param server The host name of the server
     * @param port Port number to use (normally 4894)
     */     
    public synchronized boolean connect(String server, int port)
    throws IOException, ProtocolException {
	connection = new Connection(server, port);
	reader = new KomTokenReader(connection.getInputStream());

	connection.write('A'); // protocol A
	connection.writeLine(new Hollerith("LatteKOM" + (clientHost != null ? "%" + clientHost : "")).toNetwork());

	 // "LysKOM\n"
	String serverResponse = new String(reader.readToken().getContents());
	if (!serverResponse.equals("LysKOM"))
	    throw(new KomProtocolException("Bad handshake: "+serverResponse));

	listener = new MessageListener(this);
	listener.addRpcReplyReceiver(this);
	listener.addAsynchMessageReceiver(this);
	listener.setAsynch(true);
	addRpcEventListener(this);
	state = STATE_CONNECTED;
	return connected = true;
    }

    /**
     * Connect to specified server on the default port (4894) and do initial handshake
     *
     * @param server The host name of the server
     */     
    public synchronized boolean connect(String server) 
    throws IOException, ProtocolException {
	return connect(server, 4894);
    }

    /**
     * Adds a listener for asynchronous messages (called through
     * the AsynchMessageReceiver interface).
     */
    public void addAsynchMessageReceiver(AsynchMessageReceiver a) {
	listener.addAsynchMessageReceiver(a);
    }


    /**
     * Return true if connected
     */    
    public boolean getConnected() {
	return connected;
    }

    /**
     * Supposed to return an array of global text number for all unreads
     * in a conference. Generally not a good idea. Not used.
     */
    public synchronized int[] getGlobalUnreadInConf(int conf)
    throws IOException {
	int pers = myPerson.getNo();
	Vector v = new Vector(100); // xxx

	for (int i=0; i<unreadMembership.length; i++) {
	    if (unreadMembership == null) continue;
	    v.addElement((Object) new Integer(unreadMembership[i].conference));
	}

	int c[] = new int[v.size()];
	Enumeration e = v.elements();
	for (int i=0; i < v.size() ; i++)
	    c[i] = ((Integer) e.nextElement()).intValue();
	return c;
    }	


    /**
     * Logs on to the LysKOM server.
     *
     * @param id ID number of the person to log in as
     * @param password corresponding password
     * @param hidden if true, session will not be broadcasted on LysKOM
     *
     * LysKOM call: login
     */
    public synchronized boolean login(int id, String password, boolean hidden)
    throws IOException {
	int rpcid = count();
	RpcCall loginCall = new RpcCall(rpcid, Rpc.C_login).
	    add(new KomToken(id)).add(new Hollerith(password)).
	    add(hidden ? "1" : "0"); // invisibility
	
	writeRpcCall(loginCall);

	RpcReply reply = waitFor(rpcid);
	loggedIn = reply.getSuccess();
	if (loggedIn) {
	    myPerson = getPersonStat(id);
	    myPerson.uconf = getUConfStat(id);
	    membership = getMembership(id, 0, 9999, new Bitstring(new boolean[] { true }));
	}
	state = STATE_LOGIN;
	return loggedIn = reply.getSuccess();
    }

    /**
     * Returns the Person object of the currently registered user.
     */
    public Person getMyPerson() {
	return myPerson;
    }

    /**
     * Updates the unreadMembership array with Membership objects for
     * conferences that _may_ contain unreads.
     */
    public void updateUnreads()
    throws IOException {
	int persNo = myPerson.getNo();
	unreads = getUnreadConfs(persNo);

	unreadMembership = new Membership[unreads.length];
	unreadTexts = new TextMapping[unreads.length];
        
	for (int i=0; i < unreads.length; i++) {
	    Membership m = queryReadTexts(persNo, unreads[i]);
	    int possibleUnreads = getUConfStat(m.conference).getHighestLocalNo() - m.lastTextRead;
	    if (possibleUnreads > 0 ) {
		unreadMembership[i] = queryReadTexts(persNo, unreads[i]);
		TextMapping tm = localToGlobal(unreads[i], m.lastTextRead+1, possibleUnreads);

		// ok, this textmapping may contain text numbers that
		// we've already read, lets purge.
		for (int j=0; j < m.readTexts.length; j++) {
		    // since tm.removePair return true or false
		    // depending on wheter the pair was in the
		    // mapping, you can use the return value to find
		    // out wether a purge took place
		    tm.removePair(m.readTexts[j]);
		}
		unreadTexts[i] = tm;
	    }
	}
    }

    /**
     * Returns the current conference (as entered by changeConference())
     */
    public int getCurrentConference() {
	return currentConference;
    }

    /**
     * Returns the conference number of the next conference that _may_ contain
     * an unread text.
     *
     * @param change if true, this method also calls changeConference()
     */
    public int nextUnreadConference(boolean change)
    throws IOException {
	int oldCurrent = currentConference;
	updateUnreads();

	for (int i=0; i<unreads.length; i++) {
	    if (currentConference == unreads[i]) {
		if (i<unreads.length-1) {
		    changeConference(unreads[i+1]);
		    return currentConference = unreads[i+1];
		}
	    }
	}
	if (unreads.length == 0) return -1;
	if (change) {
	    changeConference(unreads[0]);
	    if (oldCurrent == currentConference) // prolly buggy
		return -1;
	    return currentConference;
	} else {
	    return unreads[0];
	}
    }

    /**
     * Returns the global text number of the next unread text in the
     * current conference. Returns -1 if there are no unread texts.
     *
     * @param updateUnread if true, also marks the returned text as read
     *
     * NOTE: This method is _very_ inefficient and expensive on the
     * server, most notably the use of localToGlobal() for each text is
     * very stupid.
     */   
    public int nextUnreadText(boolean updateUnread)
    throws IOException {
	if (currentConference == -1 || unreads.length == 0 || unreads[0] == 0)
	    return -1;
	

	UConference c = getUConfStat(currentConference);
	Membership m = queryReadTexts(myPerson.getNo(), currentConference, true);
	if (c.getHighestLocalNo() > m.lastTextRead) {
	    int localNo = m.lastTextRead + 1;
	    TextMapping tm = localToGlobal(currentConference, localNo, 1);
	    if (!tm.hasMoreElements()) return -1;
	    int txtNo = ((Integer) tm.nextElement()).intValue();
	    if (txtNo == 0) return lastText = nextUnreadText(updateUnread);

	    if (updateUnread) {
		int[] ur = { getText(txtNo).getLocal(currentConference) };
		markAsRead(currentConference, ur);
		if (!readTexts.exists(txtNo))
		    readTexts.add(txtNo);
	    }
	    return lastText = txtNo;

	} else return -1;
    }

    /**
     * Returns a ReadTextsMap object.
     * The ReadTextsMap should be rethought.
     */
    public ReadTextsMap getReadTexts() { return readTexts; }

    /**
     * Marks a text as read on the LysKOM server. Note that
     * the text number is local.
     *
     * @param confNo conference number
     * @param localTextNo local text number
     *
     * LysKOM call: mark-as-read
     */
    public void markAsRead(int confNo, int[] localTextNo)
    throws IOException {
	RpcReply r = waitFor(doMarkAsRead(confNo, localTextNo).getId());
	if (!r.getSuccess()) throw r.getException();
    }

    /**
     * Constructs and sends the RPC call for markAsRead().
     */
    public synchronized RpcCall doMarkAsRead(int confNo, int[] localTextNo)
    throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_mark_as_read);
	req.add(new KomToken(confNo)).add(new KomTokenArray(localTextNo.length, localTextNo));
	writeRpcCall(req);
	return req;
    }


    /**
     * Returns a Text object corresponding to the specified global
     * text number. If the text has been cached earlier, the cached
     * copy will always be returned.
     *
     * @param textNo Global text number
     *
     * LysKOM call: get-text
     */
    public synchronized Text getText(int textNo)
    throws IOException { // and NoSuchTextException (RpcException?)
	if (textNo == 0) throw new RuntimeException("attempt to retreive text zero");
	Text text = textCache.get(textNo);

	// need to make sure that the text has contents,
	// since we also store TextStat skeletons in the Text cache.
	if (text != null && text.getContents() != null)
	    return text.setCached(true);

	if (text == null) text = new Text(textNo);

	Debug.println("** getting text " + textNo);

	if (text.getStat() == null) text.setStat(getTextStat(textNo));
	if (text.getStat() == null) return null; // no such text

	RpcCall textReq = new RpcCall(count(), Rpc.C_get_text).
	    add(new KomToken(textNo)).add("0").
	    add(new KomToken(text.getStat().chars));

	writeRpcCall(textReq);

	RpcReply reply = waitFor(textReq.getId());
	text.setContents(reply.getParameters()[0].getContents());
	textCache.add(text);
	return text;

    }

    /**
     * Writes an RPC call constructed from an RpcCall object to the
     * network output stream.
     *
     * @param c RpcCall object to be sent to the server
     */
    public RpcCall writeRpcCall(RpcCall c)
    throws IOException {
	rpcHeap.addRpcCall(c);
	c.writeNetwork(connection.getOutputStream());
	return c;
    }


    public RpcCall doLocalToGlobal(int confNo, int firstLocalNo,
						int noOfExistingTexts)
    throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_local_to_global);
	req.add(new KomToken(confNo)).add(new KomToken(firstLocalNo)).
	    add(new KomToken(noOfExistingTexts));
	writeRpcCall(req);
	return req;
    }

    /**
     * Returns a TextMapping that can be used to convert local text
     * number in a conference to global text numbers.
     *
     * See the "local-to-global" RPC call and the "Text-Mapping" data
     * structure in the LysKOM specification for more information.
     *
     * See doLocalToGlobal()
     *
     * LysKOM call: local-to-global, but without the limitation that
     * noOfExistingTexts must be between 1-255 inclusive
     */
    public  TextMapping localToGlobal(int confNo, int firstLocalNo,
						  int noOfExistingTexts)
    throws IOException {
	TextMapping m = new TextMapping();
	int offset = 0;
	int existingTextsLeft;
	//if noOfExistingTexts is larger than 255, break up in several calls

	// this code could probably be a lot more legible
	do {
	    existingTextsLeft = noOfExistingTexts - offset;
	    RpcReply r = waitFor(doLocalToGlobal(confNo, firstLocalNo+offset, ((existingTextsLeft > 255) ? 255 : existingTextsLeft)).getId());
	    m.update(0, r.getParameters(), false);
	    offset += 255; 
	} while ((noOfExistingTexts - offset) > 0);
	return m;
		 
    }


    /** query-read-texts **/
    public  RpcCall doQueryReadTexts(int persNo, int confNo)
    throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_query_read_texts);
	req.add(new KomToken(persNo)).add(new KomToken(confNo));
	writeRpcCall(req);
	return req;
    }

    public  Membership queryReadTexts(int persNo, int confNo)
    throws IOException {
	return queryReadTexts(persNo, confNo, false);
    }
    public  Membership queryReadTexts(int persNo, int confNo,
						  boolean refresh)
    throws IOException {
	Membership m = null;
	if (!refresh && persNo == myPerson.getNo()) {
	    m = membershipCache.get(confNo);
	    if (m != null) return m;
	}
	m = new Membership(0, 
	    waitFor(doQueryReadTexts(persNo, confNo).getId()).
	    getParameters());
	return membershipCache.add(m);
    }

    /** get-unread-confs **/
    public  RpcCall doGetUnreadConfs(int persNo)
    throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_get_unread_confs).
	    add(new KomToken(persNo));
	writeRpcCall(req);
	return req;
    }
    public  int[] getUnreadConfs(int persNo)
    throws IOException {
	KomToken[] parameters = waitFor(doGetUnreadConfs(persNo).getId()).
	    getParameters();
	KomToken[] confs = ((KomTokenArray) parameters[1]).getTokens();
	int[] iconfs = new int[confs.length];
	for (int i=0; i<iconfs.length; i++)
	    iconfs[i] = confs[i].toInteger();
	return iconfs;
    }
	
	    
    /** get-membership **/
    public  RpcCall doGetMembership(int persNo, int first,
						int no, Bitstring mask)
    throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_get_membership).
	    add(new KomToken(persNo)).add(new KomToken(first)).
	    add(new KomToken(no)).
	    add((KomToken) mask);
	writeRpcCall(req);
	return req;
    }
    public RpcCall doGetMembership(int persNo)
    throws IOException {
	return doGetMembership(persNo, 0, 1000, new Bitstring("0"));
    }

    public  Membership[] getMembership(int persNo, int first,
				       int no, Bitstring mask)
    throws IOException {
	return Membership.createFrom(waitFor(doGetMembership(persNo, first,
							     no, mask)
					     .getId()));
    }

    public  RpcCall doCreateConf(String name, Bitstring type, AuxItem[] auxItems)
    throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_create_conf);
	req.add(new Hollerith(name));
	req.add(type);
	KomToken[] auxArray = new KomToken[auxItems.length];
	for (int i=0; i < auxItems.length; i++) {
	    auxArray[i] = auxItems[i].toToken();
	}
	req.add((KomToken) new KomTokenArray(auxItems.length, auxArray));
	writeRpcCall(req);
	return req;
    }

    public  int createConf(String name, boolean readProt, boolean original, boolean secret)
    throws IOException {
	Bitstring confType = new Bitstring(new boolean[] { readProt, original, secret, false });
	RpcReply r = waitFor(doCreateConf(name, confType, new AuxItem[] {}).getId());
	if (r.getSuccess()) {
	    return r.getParameters()[0].toInt();
	}
	return -1;
    }
					     

    public  Membership[] getMembership(int persNo)
    throws IOException {
	return Membership.createFrom(waitFor(doGetMembership(persNo).getId()));
    }

    public  Membership[] getUnreadMembership() {
	return unreadMembership;
    }
    /**
     * return array of unread texts (in TextMapping for) for all
     * conferences. You should call updateUnreads before this */
    public  TextMapping[] getUnreadTexts() {
	return unreadTexts;
    }
    
    /** get-uconf-stat **/
    public  RpcCall doGetUconfStat(int confNo)
    throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_get_uconf_stat).
	    add(new KomToken(confNo));

	writeRpcCall(req);
	return req;
    }
    public UConference getUConfStat(int confNo)
    throws IOException, RpcFailure {
	UConference cc = conferenceCache.getUConference(confNo);
	if (cc != null) return cc;
	RpcCall req = doGetUconfStat(confNo);	
	Debug.println("uconf-stat for " + confNo + " not in cache, asking server");
	RpcReply rep = waitFor(req.getId());
	if (rep.getSuccess()) {	    
	    cc = new UConference(confNo, rep.getParameters());	
	    conferenceCache.add(cc);
	    return cc;
	} else {
	    throw rep.getException();
	}
    }

    public  void endast(int no) 
    throws IOException {
	endast(currentConference, no);
    }

    public  void endast(int confNo, int no)
    throws IOException {
	int highest = getUConfStat(confNo).getHighestLocalNo();
	setLastRead(confNo, highest-no);
    }

    /** set-last-read **/
    public  RpcCall doSetLastRead(int confNo, int textNo) 
    throws IOException {
	return writeRpcCall(new RpcCall(count(), Rpc.C_set_last_read).
			    add(new KomToken(confNo)).
			    add(new KomToken(textNo)));
    }

    public  void setLastRead(int confNo, int textNo)
    throws IOException {
	waitFor(doSetLastRead(confNo, textNo).getId());
    }


    /** get-conf-stat **/
    public  RpcCall doGetConfStat(int confNo)
    throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_get_conf_stat).
	    add(new KomToken(confNo));

	writeRpcCall(req);
	return req;
    }
    public  Conference getConfStat(int confNo)
    throws IOException {
	Conference cc = conferenceCache.getConference(confNo);
	if (cc != null) return cc;

	RpcReply reply = waitFor(doGetConfStat(confNo).getId());

	Conference c = new Conference(confNo, reply.getParameters());
	conferenceCache.add(c);
	return c;
    }

    /** change-conference **/
    public  void changeConference(int confNo)
    throws IOException, RpcFailure {
	RpcReply reply = waitFor(doChangeConference(confNo).getId());
	if (reply.getSuccess()) {
	    currentConference = confNo;
	} else {
	    throw reply.getException();
	}
    }

    public  RpcCall doChangeConference(int confNo)
    throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_change_conference)
	    .add(confNo);
	writeRpcCall(req);
	return req;
    }

    public RpcCall doAddMember(int confNo, int persNo, int prio, int listPos, Bitstring type) throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_add_member);
	req.add(new KomToken(confNo));
	req.add(new KomToken(persNo));
	req.add(new KomToken(prio));
	req.add(new KomToken(listPos));
	req.add(type);
	writeRpcCall(req);
	return req;
    }

    public void addMember(int confNo, int persNo, int prio, int listPos, boolean invitation,
			  boolean passive, boolean secret)
    throws IOException, RpcFailure {
	RpcReply reply = waitFor(doAddMember(confNo, persNo, prio, listPos,
					     new MembershipType(new boolean[] { invitation, passive, secret })).getId());
	if (!reply.getSuccess()) {
	    throw reply.getException();
	}
    }

    public RpcCall doChangeName(int confNo, String newName)
    throws IOException, RpcFailure {
	RpcCall req = new RpcCall(count(), Rpc.C_change_name);
	req.add(new KomToken(confNo));
	req.add(new Hollerith(newName));
	writeRpcCall(req);
	return req;
    }

    public void changeName(int confNo, String newName)
    throws IOException, RpcFailure {
	RpcReply reply = waitFor(doChangeName(confNo, newName));
	if (!reply.getSuccess()) throw reply.getException();
    }

    public void joinConference(int confNo) throws IOException, RpcFailure {
	addMember(confNo, getMyPerson().getNo(), 100, membership.length, false, false, false);
    }

    /** get-person-stat **/
    public  RpcCall doGetPersonStat(int persNo)
    throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_get_person_stat).
	    add(persNo);
	
	writeRpcCall(req);
	return req;
    }
    public  Person getPersonStat(int persNo)
    throws IOException {
	Person p = personCache.get(persNo);
	if (p != null) return p;

	RpcReply reply = waitFor(doGetPersonStat(persNo).getId());

	p = Person.createFrom(persNo, reply);
	personCache.add(p);

	return p;
    }
	

    /**
     * Returns a byte-array containing the name for a conference,
     * or null if the conference doesn't exist.
     *
     * @param confNo conference number to retreive the name for
     */
    public  byte[] getConfName(int confNo)
    throws IOException {
	if (confNo == 0)
	    return "Conference 0".getBytes();
	UConference c = getUConfStat(confNo);
	
	return c == null ? null : c.getName();
    }

    public  RpcCall doGetTextStat(int textNo)
    throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_get_text_stat).
	    add(new KomToken(textNo));

	writeRpcCall(req);
	return req;
    }

    /**
     * Returns a TextStat object for a given text number,
     * or null if the text doesn't exist or is inaccessible.
     *
     * As with getText(), getTextStat() also currently always 
     * returns a cached object if available.
     *
     * LysKOM call: get-text-stat
     */
    public  TextStat getTextStat(int textNo)
    throws IOException {

        TextStat ts = textStatCache.get(textNo);
        if (ts != null) return ts;

	Text text = textCache.get(textNo);
	if (text != null && text.getStat() != null)
	    return text.getStat();

	RpcReply reply = waitFor(doGetTextStat(textNo).getId());
	if (reply.getSuccess()) {
	    ts = TextStat.createFrom(textNo, reply);
	    textStatCache.add(ts);
	    return ts;
	} else {
	    int error = reply.getException().getError();
	    if (error == Rpc.E_no_such_text) return null;
	    throw reply.getException();
	}

    }
    
    public boolean getLoggedIn() {
	return loggedIn;
    }

    public  void logout(boolean block)
    throws IOException {
	RpcCall logoutCall = new RpcCall(count(), Rpc.C_logout);

	writeRpcCall(logoutCall);
	loggedIn = false;
	state = STATE_CONNECTED;
	if (!block) return;
	
	waitFor(logoutCall.getId());
    }

    public void writeRaw(int id, String s) throws IOException {
	int seq = count();
	OutputStream out = connection.getOutputStream();
	byte[] bytes = (seq + " " + id + ((s != null) ? " " + s : "") + "\n")
	    .getBytes();
	    Debug.println("raw RPC call: " + new String(bytes));
	out.write(bytes);
		 
    }

    private synchronized int count() {
	return ++rpcCount;
    }

    public KomTokenReader getKomTokenReader() {
	return reader;
    }

    public  RpcCall doCreateText(Text t)
    throws IOException {
	t.trimContents();
	t.getStat().addAuxItem(new AuxItem(AuxItem.tagCreatingSoftware,
					   new Bitstring("00000000"), 0,
					   new Hollerith("lattekom 0.0")));
	return doCreateText(t.getContents(), t.getStat().getMiscInfo(),
			    t.getStat().getAuxItems());
    }


    /**
     * Very simple reply function. Application developers are encouraged to 
     * use createText() instead
     */
    public  int reply(int textNo, Text t)
    throws IOException {
	Text commented = getText(textNo);
	int[] rcpts = commented.getRecipients();
	for (int i=0; i < rcpts.length; i++) 
	    t.addRecipient(rcpts[i]);
	t.addCommented(textNo);
	return createText(t);
    }

    /**
     * Creates a text on the LysKOM server. Sort of works, actually.
     *
     * @param text byte-array containing the article text
     * @param miscInfo Selection with Misc-Info stuff (recipients, etc)
     * @param auxItems Auxiallry items (Aux-Item) attached to this article
     *
     * LysKOM call: create-text
     */
    public  RpcCall doCreateText(byte[] text,
					     Selection  miscInfo,
					     AuxItem[] auxItems)
    throws IOException {
	KomToken[] ar = { miscInfo.toToken() };	
	KomTokenArray auxTokenArray;

	if (auxItems != null && AuxItem.countAuxItems(auxItems) > 0) {
	    KomToken[] auxar = null;
	    auxar = new KomToken[auxItems.length];
	    for (int i=0; i<auxItems.length; i++)
		auxar[i] = auxItems[i].toToken();
	    auxTokenArray = new KomTokenArray(auxItems.length, auxar);
	} else {
	    auxTokenArray = new KomTokenArray(0);
	}

	RpcCall req = new RpcCall(count(), Rpc.C_create_text).
	    add(new Hollerith(text)).
	    add((KomToken) new KomTokenArray(miscInfo.size(), ar)).
	    add((KomToken) auxTokenArray);
	writeRpcCall(req);
	return req;	
    }

    /**
     * Wrapper for other createText()
     */
    public  int createText(Text t)
    throws IOException {
	RpcReply reply = waitFor(doCreateText(t).getId());

	if (!reply.getSuccess())
	    throw reply.getException();

	return reply.getParameters()[0].toInteger();
    }

    /**
     * Wrapper for other createText()
     */
    public  int createText(String subject, String body, int rcpt)
    throws IOException {
	return createText(subject + "\n" + body, rcpt);
    }

    /**
     * Another method for creating text. Doesn't work too well,
     * should be removed, I guess.
     */
    public  int createText(String text, int rcpt)
    throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_create_text);
	Selection miscInfo = new Selection(15);
	miscInfo.add(0, (Object) new Integer(rcpt));
	req.add(new Hollerith(text));
	KomToken[] miscInfoArray = { miscInfo.toToken() };
	KomTokenArray kar = new KomTokenArray(1, miscInfoArray);
	req.add((KomToken) kar);

	KomTokenArray auxTokens = new KomTokenArray(0, emptyKomTokens);
	req.add((KomToken) auxTokens);

	writeRpcCall(req);

	RpcReply reply = waitFor(req.getId());
	if (!reply.getSuccess())
	    return -1;
	return reply.getParameters()[0].toInteger();
    }

    private RpcReply waitFor(RpcCall r) throws IOException {
	return waitFor(r.getId());
    }

    public RpcCall doChangeWhatIAmDoing(String s) throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_change_what_i_am_doing);
	req.add(new Hollerith(s));
	writeRpcCall(req);
	return req;
    }

    public void changeWhatIAmDoing(String s) throws IOException {
	RpcReply reply = waitFor(doChangeWhatIAmDoing(s));
	if (!reply.getSuccess()) throw reply.getException();
    }


    /** who-is-on-dynamic **/
    public synchronized RpcCall doWhoIsOnDynamic (boolean wantVisible, boolean wantInvisible, int activeLast)
    throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_who_is_on_dynamic);
	req.add(wantVisible ? "1" : "0").
	    add(wantInvisible ? "1" : "0").
	    add(activeLast);
	writeRpcCall(req);
	return req;
    }

    public DynamicSessionInfo[] whoIsOnDynamic (boolean wantVisible, boolean wantInvisible, int activeLast)
	throws IOException {
	Debug.println("whoIsOnDynamic called");
	RpcReply reply = waitFor(doWhoIsOnDynamic(wantVisible, wantInvisible, activeLast).getId());
	Debug.println("waitFor returned");

	KomToken[] parameters = reply.getParameters();
	DynamicSessionInfo[] ids = new DynamicSessionInfo[parameters[0].toInteger()];
	KomToken[] sessionData = ((KomTokenArray) parameters[1]).getTokens();


	for (int i=0, j=5 ; i < ids.length ; i++, j = j + 6)
	    ids[i] = new DynamicSessionInfo(sessionData[j-5].toInteger(),
					    sessionData[j-4].toInteger(),
					    sessionData[j-3].toInteger(),
					    sessionData[j-2].toInteger(),
					    new Bitstring(sessionData[j-1]),
					    sessionData[j].getContents());
	
	
	return ids;
    }


    public RpcCall doDeleteText(int textNo) throws IOException {
	RpcCall req = new RpcCall(count(), Rpc.C_delete_text).add(new KomToken(textNo));
	writeRpcCall(req);
	return req;
    }

    public void deleteText(int textNo)throws IOException, RpcFailure {
	RpcReply reply = waitFor(doDeleteText(textNo));
	if (!reply.getSuccess()) throw reply.getException();
    }


    /**
     * lookup names of persons/conferences. 
     *
     * @param name (sub)string naming the person(s)/conference(s)
     * @param wantPersons Return persons matching @tt{name}
     * @param wantConfs Return conferences matching @tt{name}
     *
     * LysKOM call: lookup-z-name
     */

    
    // TODO: return Conf-Z-Info-List (ConfInfo[]?)
    public synchronized ConfInfo[] lookupName(String name, boolean wantPersons,
					      boolean wantConfs)
    throws IOException {
	int id = count();
	RpcCall lcall = new RpcCall(id, Rpc.C_lookup_z_name);
	lcall.add(new Hollerith(name)).
	    add(wantPersons ? "1" : "0").
	    add(wantConfs ? "1" : "0");


	writeRpcCall(lcall);

	RpcReply reply = waitFor(id);

	if (!reply.getSuccess()) {
	    return null;
	}
	KomToken[] parameters = reply.getParameters();
	ConfInfo[] ids = new ConfInfo[parameters[0].toInteger()];
	KomToken[] confData = ((KomTokenArray) parameters[1]).getTokens();

	for (int i=0, j=2 ; i < ids.length ; i++, j = j + 3)
	    ids[i] = new ConfInfo(confData[j-2].getContents(),
				  new ConfType(confData[j-1]),
				  confData[j].toInteger());

	return ids;
    }

    public boolean sendMessage(int recipient, String message)
    throws IOException, RpcFailure {
	RpcCall msgCall = new RpcCall(count(), Rpc.C_send_message).
	    add(new KomToken(recipient)).add(new Hollerith(message));

	writeRpcCall(msgCall);
	RpcReply reply = waitFor(msgCall.getId());
	if (!reply.getSuccess()) throw reply.getException();
	return true;
    }

    /**
     * This methods provides a synchronous way of waiting for RPC
     * replies. Note that this method should never be called from
     * the MessageListener thread (for example, you may not call
     * this method from inside an AsynchMessageListener's
     * asyncMessage() method.
     */
    private RpcReply waitFor(int id)
    throws IOException {
	RpcCall rcfjant = null;
	RpcReply reply = null;
	int waited = 0;
	while (rcfjant == null) {
	    synchronized (rpcHeap) {
		try {
		    Debug.println("Waiting for RPC reply to command #" + id);
		    if (Thread.currentThread() == listener.getThread()) {
			throw new IOException("waitFor() called from asynch dispatcher thread");
		    } else {
			rpcHeap.wait(DEFAULT_TIMEOUT);
		    }

		} catch (InterruptedException ex1) {
		    // ...?
		}
		rcfjant = rpcHeap.getRpcCall(id, true);
	    }

	    if (rcfjant == null) {
		wakeOnReplyFrom = id;
		waited += DEFAULT_TIMEOUT;
		if (waited > MAX_TIMEOUT) {
		    IOException e = new IOException("Time out waiting for RPC reply #"+id);
		    e.printStackTrace();
		    throw(e);
		}
	    } else {
		reply = rcfjant.getReply();
	    }
	}
	wakeOnReplyFrom = -1;
	return reply;
    }



    public void rpcEvent(RpcEvent e) {
	switch(e.getOp()) {
	case Rpc.C_query_read_texts:
	    int conf = e.getCall().getParameter(1).toInteger();
	    Membership m = membershipCache.get(conf);
	    if (m == null) {
		m = membershipCache.add(new Membership(0,
						       e.getReply().
						       getParameters()));
	    }
	    break;
	}
    }

    public void rpcReply(RpcReply r) {
	if (false) {
	    if (r.getSuccess())
		Debug.print("OK, ");
	    else
		Debug.print("ERROR, ");

	    Debug.print("ID="+r.getId());
	    KomToken[] foo = r.getParameters();
	    for (int i=0;i<foo.length;i++) {
		Debug.print(" ["+foo[i].toString()+"]");
	    }
	    Debug.println("!");
	}

	RpcCall originCall = rpcHeap.getRpcCall(r.getId(), false);
	if (originCall != null) {
	    originCall.setReply(r);
	}

	if (wakeOnReplyFrom == r.getId())
	    mainThread.interrupt();

	if (originCall != null) {
	    notifyRpcEventListeners(new RpcEvent(this, originCall));
	}

    }

    /**
     * http://www.lysator.liu.se/lyskom/protocol/10.4/protocol-a.html#About%20Asynchronous%20Messages
     *
     * An asynchronous message is sent as a colon immediately followed by the
     * number of message parameters, the message number and the message
     * parameters. For example, message number 5 could be sent as:
     * <pre>   :3 5 119 11HDavid Byers 13HDavid C Byers</pre>
     *
     *
     */
    public void asynchMessage(AsynchMessage m) {
	Debug.println("Session.asynchMessage(): "+m.toString());

	KomToken[] parameters = m.getParameters();

	int textNo = 0;
	
	switch (m.getNumber()) {
	case Asynch.login:
	    Debug.println("asynch-login");
	    break;
	case Asynch.new_text_old:
	    /* if the new text is a comment to a previously read and cached text,
	     * the cached copy of the commented text's text-stat must be
	     * invalidated or refreshed
	     */
	    textNo = parameters[0].toInt();
	    Debug.println("async-new-text-old for text " + textNo);
	    break;
	case Asynch.new_name:
	    conferenceCache.removeAll(parameters[0].toInt());
	    break;
	case Asynch.deleted_text:
	    textNo = parameters[0].toInt();
	    Debug.println("async-deleted-text for text " + textNo);
	    textCache.remove(textNo);
	    break;

	case Asynch.new_recipient:
	    textNo = parameters[0].toInt();
	    Debug.println("async-new-recipient for text " + textNo);
	    textCache.remove(textNo);
	    break;

	case Asynch.sub_recipient:
	    textNo = parameters[0].toInt();
	    Debug.println("async-sub-recipient for text " + textNo);	    
	    textCache.remove(textNo);
	    break;	    
	}

    }

    

}
