/**! -*- Mode: Java; c-basic-offset: 4 -*-
 *
 * Copyright (c) 1999 by Rasmus Sten <rasmus@sno.pp.se>
 *
 */
package nu.dll.lyskom;

import java.util.Hashtable;


public class MembershipCache {
    final static int DEBUG = 0;
    Hashtable hash;
    
    public MembershipCache() {
	hash = new Hashtable();
    }

    public Membership add(Membership p) {
	if (p.getNo() == -1)
	    return null; // throw(new MembershipNumberException("Membership has no number"));

	if (DEBUG > 0) Debug.println("MembershipCache: adding "+p.getNo());

	if (hash.put((Object) new Integer(p.getNo()), (Object) p)!=null) {
	    if (DEBUG > 0) Debug.println("MembershipCache: " +
					      "replacing Membership #" +
					      p.getNo()+" in cache");
	}
	return p;
    }

    public Membership get(int membershipNo) {
	Membership p = (Membership) hash.get(new Integer(membershipNo));
	if (p != null) {
	    if (DEBUG > 0) {
		Debug.println("MembershipCache: returning "+membershipNo);
	    }
	} 
	return p;
    }
}