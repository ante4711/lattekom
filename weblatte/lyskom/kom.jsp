<%@ page language='java' import='nu.dll.lyskom.*, java.text.*, java.util.*' %>\
<%@ page import='java.util.regex.*, java.io.*' %>\
<%!
    String basePath = "/lyskom/"; // the absolute path on the webserver
    String appPath = "/lyskom/"; // the weblatte root within the web application

    static class KomServer {
        public String hostname;
        public String name;
        public KomServer(String hostname, String name) {
	    this.hostname = hostname;
	    this.name = name;
	}
    }

    static class Servers {
    	public static List list  = new LinkedList();
    	public static KomServer defaultServer;
	static {
	    if (Debug.ENABLED) list.add(defaultServer = new KomServer("localhost", "RasmusKOM"));
	    list.add(new KomServer("sno.pp.se", "SnoppKOM"));
	    list.add(new KomServer("kom.lysator.liu.se", "LysLysKOM"));
	    list.add(new KomServer("plutten.dnsalias.org", "PluttenKOM"));
	    list.add(new KomServer("kom.update.uu.se", "UppKOM"));
            defaultServer = (KomServer) list.get(0);
	}
    }

    static class PreferenceMetaData {
	public String key, description, block, type, defaultValue;
	public PreferenceMetaData(String key, String description,
				  String block, String type, String defaultValue) {
	    this.key = key;
	    this.description = description;
	    this.block = block;
	    this.type = type;
	    this.defaultValue = defaultValue;
	}
    }


    static class PreferencesMetaData {
	public static List list = new LinkedList();
	public static Map blocks = new HashMap();
	public static Map blockKeys = new HashMap();
	static {
	    list.add(new PreferenceMetaData("created-texts-are-read",
					    "Markera skapade texter som l�sta",
					    "common", "boolean", "1"));
	    list.add(new PreferenceMetaData("dashed-lines",
					    "Visa streck kring inl�ggskroppen",
					    "common", "boolean", "1"));
	    list.add(new PreferenceMetaData("print-number-of-unread-on-entrance",
					    "Visa antal ol�sta vid inloggning",
					    "common", "boolean", "1"));

	    list.add(new PreferenceMetaData("hide-standard-boxes",
					    "D�lj standardboxarna f�r endast, l�sa inl�gg och s�nda meddelande",
					    "weblatte", "boolean", "0"));
	    list.add(new PreferenceMetaData("show-plain-old-menu",
					    "Visa textmenyer",
					    "weblatte", "boolean", "0"));
	    list.add(new PreferenceMetaData("always-show-welcome",
					    "Visa alltid v�lkomsttext",
					    "weblatte", "boolean", "1"));
	    list.add(new PreferenceMetaData("auto-refresh-news",
					    "Uppdatera nyhetslista automatiskt",
					    "weblatte", "boolean", "1"));
	    list.add(new PreferenceMetaData("start-in-frames-mode",
					    "Starta med ramvy",
					    "weblatte", "boolean", "0"));
	    list.add(new PreferenceMetaData("my-name-in-bold",
					    "Visa mitt eget namn i fetstil",
					    "weblatte", "boolean", "0"));

	    for (Iterator i = list.iterator(); i.hasNext();) {
		PreferenceMetaData pmd = (PreferenceMetaData) i.next();
		List blockList = (List) blocks.get(pmd.block);
		if (blockList == null) blockList = new LinkedList();
		Map blockMap = (Map) blockKeys.get(pmd.block);
		if (blockMap == null) blockMap = new HashMap();
		blockList.add(pmd);
		blockMap.put(pmd.key, pmd);
		blocks.put(pmd.block, blockList);
		blockKeys.put(pmd.block, blockMap);
	    }
	    list = Collections.unmodifiableList(list);
	    blocks = Collections.unmodifiableMap(blocks);
	}

	static String getDefault(String blockName, String key) {
	    Map block = (Map) blockKeys.get(blockName);
	    if (block == null)
		throw new IllegalArgumentException("Bad block \"" + blockName + "\"");
	    if (!block.containsKey(key))
		throw new IllegalArgumentException("Block \"" + 
				blockName + "\" does not have a key \"" +
				key + "\"");
	    PreferenceMetaData pmd = (PreferenceMetaData) block.get(key);
	    return pmd.defaultValue;
	}

	static boolean containsKey(String blockName, String key) {
	    Map block = (Map) blockKeys.get(blockName);
	    if (block == null)
		throw new IllegalArgumentException("Bad block \"" + blockName + "\"");
	    return block.containsKey(key);
	}
    }

    static class KomPreferences {
	HollerithMap map;
        String blockName;
	public KomPreferences(HollerithMap map, String blockName) {
	    this.map = map;
 	    this.blockName = blockName;
	}

	public boolean getBoolean(String key) {
	    if (!map.containsKey(key)) {
		String defaultValue = PreferencesMetaData.getDefault(blockName, key);
		return defaultValue.equals("1");
	    }
	    return map.get(key).equals("1");
	}

	public String getString(String key) {
	    if (!map.containsKey(key)) {
		return PreferencesMetaData.getDefault(blockName, key);
	    }
	    return map.get(key).getContentString();
	}

	public void set(String key, String value) {
	    map.put(key, value);
	}

	public Hollerith getData() {
	    return map;
	}
    }

    public KomPreferences preferences(Session lyskom, String blockName) throws IOException, RpcFailure {
	UserArea userArea = lyskom.getUserArea();
	Hollerith data = userArea.getBlock(blockName);
	if (data != null) {
	    return new KomPreferences(new HollerithMap(data), "weblatte");
	} else {
	    return new KomPreferences(new HollerithMap(lyskom.getServerEncoding()), "weblatte");
	}
    }


    final static int SORT_FILEID = 1, SORT_MODIFIED = 2, SORT_MUGNAME = 3;
    Collator collator = Collator.getInstance(new Locale("sv", "SE"));
    Pattern komNamePat = Pattern.compile("\\(.*?\\)");

    public int compareFiles(File f1, File f2, int sortBy) throws NumberFormatException {
	if (sortBy == SORT_MUGNAME) {
	    try {
		LineNumberReader reader = 
			new LineNumberReader(new InputStreamReader(new FileInputStream(f1)));
		String name1 = komNamePat.matcher(reader.readLine()).replaceAll("").trim();
		reader.close();
		reader = 
			new LineNumberReader(new InputStreamReader(new FileInputStream(f2)));
		String name2 = komNamePat.matcher(reader.readLine()).replaceAll("").trim();
		reader.close();
		return collator.compare(name1, name2);
	    } catch (IOException ex1) {
		throw new RuntimeException(ex1);
	    }

	}
	int n1 = sortBy == SORT_FILEID ? Integer.parseInt(f1.getName().substring(0, f1.getName().indexOf("."))) : (int) (f1.lastModified()/1000l);
	int n2 = sortBy == SORT_FILEID ? Integer.parseInt(f2.getName().substring(0, f2.getName().indexOf("."))) : (int) (f2.lastModified()/1000l);
	int r = 0;
	if (n1 < n2) r = -1;
	if (n1 > n2) r = 1;
	if (sortBy == SORT_MODIFIED) r = -r; // reverse
	return r;
    }


    static class Mugshot {
	public String id = null, name = null, image = null;
	public File file;
	public Mugshot(File _file) throws IOException {
	    this.file = _file;
	    this.id = file.getName().substring(0,file.getName().indexOf("."));
	    LineNumberReader reader = new LineNumberReader(new FileReader(file));
	    this.name = reader.readLine();
	    this.image = reader.readLine();
	}
    }

    int rightMargin = Integer.getInteger("lattekom.linewrap", new Integer(70)).intValue();
    void wrapText(Text newText) throws UnsupportedEncodingException {
	java.util.List rows = newText.getBodyList();
	java.util.List newRows = new LinkedList();

	Iterator i = rows.iterator();
	while (i.hasNext()) {
	    String row = (String) i.next();
	    boolean skip = false;
	    while (!skip && row.length() > rightMargin) {
		int cutAt = row.lastIndexOf(' ', rightMargin);
		if (cutAt == -1) { // can't break row
		    skip = true;
		    continue;
		}
		String wrappedRow = row.substring(0, cutAt);
		row = row.substring(cutAt+1);
		newRows.add(wrappedRow);
	    }
	    newRows.add(row);
	}

	i = newRows.iterator();
	StringBuffer newBody = new StringBuffer();
	while (i.hasNext()) {
	    String row = (String) i.next();
	    newBody.append(row + "\n");
	}
	newText.setContents((new String(newText.getSubject(), newText.getCharset()) + "\n" +
			 newBody.toString()).getBytes(newText.getCharset()));	
    }

    String makeAbsoluteURL(String path) {
        if ("".equals(path)) return basePath;
        if (path.startsWith("/")) path = path.substring(1);
        return basePath + path;
    }

    String serverShort(Session lyskom) {
	String host = lyskom.getServer().toLowerCase();
        for (Iterator i = Servers.list.iterator(); i.hasNext();) {
            KomServer s = (KomServer) i.next();
            if (host.equals(s.hostname)) return s.name;
        }
	return lyskom.getServer();
    }

    // makes the given string suitable for HTML presentation
    Pattern weblinkPat = Pattern.compile("(http://[^ \t\r\n\\\\\\[><,!]{3,}[^ ?.,)>])");
    String htmlize(String s) {
	s = s.replaceAll("&", "&amp;").replaceAll("<", "&lt;");
	return weblinkPat.matcher(s).replaceAll("<a href=\"$1\">$1</a>");
    }

    Pattern dqesc = Pattern.compile("\"");
    Pattern sqesc = Pattern.compile("'");
    public String dqescHtml(String s) {
	return dqesc.matcher(s).replaceAll("&quot;");
    }
    public String dqescJS(String s) {
	return dqesc.matcher(s).replaceAll("\\\\\"");
    }
    public String sqescJS(String s) {
	return sqesc.matcher(s).replaceAll("\\\\'");
    }

    public String jsTitle(String title) {
	return new StringBuffer("<script language=\"JavaScript1.2\">")
	    .append("document.title = \"")
	    .append(dqescJS(title))
	    .append("\";</script>").toString();
    }


    String lookupNamePlain(Session lyskom, int number)
    throws RpcFailure, IOException {
	String name = "[" + number + "]";
	UConference uconf = null;
	try {
	    uconf = lyskom.getUConfStat(number);
	    name = lyskom.toString(uconf.getName());
	}  catch (RpcFailure ex1) {
	    if (ex1.getError() != Rpc.E_undefined_conference)
		throw ex1;
	}
	return name;
    }

    String lookupName(Session lyskom, int number, boolean useHtml)
    throws RpcFailure, IOException {
	if (useHtml) return lookupNameHtml(lyskom, number);
	else return lookupNamePlain(lyskom, number);
    }

    String lookupName(Session lyskom, int number)
    throws RpcFailure, IOException {
	return lookupName(lyskom, number, false);
    }


    String lookupNameHtml(Session lyskom, int number)
    throws RpcFailure, IOException {
	String name = "[" + number + "]";
	Conference conf = null;
	try {
	    conf = lyskom.getConfStat(number);
	    name = lyskom.toString(conf.getName());
	} catch (RpcFailure ex1) {
	    if (ex1.getError() != Rpc.E_undefined_conference)
		throw ex1;
	}
	if (conf != null) {
	    boolean isMe = lyskom.getMyPerson().getNo() == number;
	    KomPreferences prefs = preferences(lyskom, "weblatte");
	    boolean bold = isMe && prefs.getBoolean("my-name-in-bold");
	    return "<span title=\"" + (conf.getType().getBitAt(ConfType.letterbox) ? "Person " : "M�te ") + conf.getNo() + "\" onMouseOut=\"context_out()\" onMouseOver=\"context_in(" + number + ", " + conf.getType().getBitAt(ConfType.letterbox) + ", false, '" + sqescJS(lyskom.toString(conf.getName())) + "');\">" + (bold ? "<b>" : "") + htmlize(name) + (bold ? "</b>" : "") + "</span>";
	} else {
	    return htmlize(name);
	}
    }


    Random rnd = new Random();
    SimpleDateFormat df = new SimpleDateFormat("EEEEE d MMMMM yyyy', klockan 'HH:mm", new Locale("sv", "se"));

    String textLink(HttpServletRequest request, Session lyskom, int textNo)
    throws RpcFailure, IOException {
	return textLink(request, lyskom, textNo, true);
    }

    String ambiguousNameMsg(Session lyskom, AmbiguousNameException ex) 
    throws RpcFailure, IOException {
	return ambiguousNameMsg(null, ex);
    }

    String ambiguousNameMsg(Session lyskom, String name, AmbiguousNameException ex)
    throws RpcFailure, IOException {
	StringBuffer buf = new StringBuffer();
	buf.append("<p class=\"statusError\">Fel: namnet �r flertydigt. F�ljande namn matchar:\n");
	buf.append("<ul>\n");
	ConfInfo[] names = ex.getPossibleNames();
	for (int i=0; i < names.length; i++) 
	    buf.append("\t<li>" + lookupName(lyskom, names[i].getNo(), true) + "\n");
	buf.append("</ul>");
	return buf.toString();
	
    }

    ConfInfo lookupName(Session lyskom, String name, boolean wantPersons, boolean wantConferences)
    throws IOException, RpcFailure, AmbiguousNameException {
	if (name.startsWith("#")) {
	    // this is quite ugly and only works with readable names
	    String nameStr = name.substring(1);
	    int confNo = Integer.parseInt(nameStr);
	    return lyskom.lookupName(lookupName(lyskom, confNo), wantPersons, wantConferences)[0];
	}
	ConfInfo[] names = lyskom.lookupName(name, wantPersons, wantConferences);
	if (names.length == 0) return null;
	if (names.length > 1) throw new AmbiguousNameException(names);
	return names[0];
    }

    String textLink(HttpServletRequest request, Session lyskom, int textNo, boolean includeName)
    throws RpcFailure, IOException {
	StringBuffer sb = new StringBuffer()
		.append("<a href=\"")
		/*.append(myURI(request))*/
		.append("/lyskom/")
		.append("?text=")
		.append(textNo);
	if (request.getParameter("conference") != null) {
		sb.append("&conference=")
		.append(request.getParameter("conference"));
	}
	sb.append("\" ")
		.append("onMouseOver=\"context_in(").append(textNo).append(", false, true);\" ")
		.append("onMouseOut=\"context_out()\" ")
		.append(">")
		.append(textNo)
		.append("</a>");
	if (includeName) {
	    try {
		String a = lookupName(lyskom, lyskom.getTextStat(textNo).getAuthor(), true);
		sb.append(" av ").append(a);
	    } catch (RpcFailure ex1) {
		if (ex1.getError() != Rpc.E_no_such_text)
		    throw ex1;
	    } 
	}
	return sb.toString();
    }

    String myURI(HttpServletRequest request) {
	String setURI = (String) request.getAttribute("set-uri");
	if (setURI != null) return setURI;
	return request.getRequestURI();
    }

    String utf8ize(String s) throws UnsupportedEncodingException {
	return new String(s.getBytes("utf-8"), "iso-8859-1");
    }
    String entitize(String s) {
	return s.
	replaceAll("�", "&#229;").
	replaceAll("�", "&#228;").
	replaceAll("�", "&#246;").
	replaceAll("�", "&#197;").
	replaceAll("�", "&#196;").
	replaceAll("�", "&#214;");
	
    }

    class AmbiguousNameException extends Exception {
	ConfInfo[] possibleNames;
	public AmbiguousNameException(ConfInfo[] names) {
	    this.possibleNames = names;
	}
	public ConfInfo[] getPossibleNames() {
	    return possibleNames;
	}
    }

    class MessageReceiver implements AsynchMessageReceiver {
	LinkedList list;
	public MessageReceiver(LinkedList list) {
	    this.list = list;
	}

	public void asynchMessage(AsynchMessage m) {
	    synchronized (list) {
		list.addLast(m);
	    }
	}
    }
%>\
<%
String dir = getServletContext().getRealPath("/lyskom/bilder/");
UserArea userArea = null;
KomPreferences commonPreferences = null;
KomPreferences preferences = null;
%>\