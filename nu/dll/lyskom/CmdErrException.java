/**! -*- Mode: Java; c-basic-offset: 4 -*-
 *
 * Copyright (c) 1999 by Rasmus Sten <rasmus@sno.pp.se>
 *
 */
package nu.dll.lyskom;

public class CmdErrException extends Exception {
    public CmdErrException(String s) {
	super(s);
    }
}	