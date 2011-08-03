/*
 * Copyright (c) 2007 Amazon.com, Inc.  All rights reserved.
 */

package com.amazon.ion;


/**
 * Signals use of an invalid symbol starting with <code>"$ion_"</code>.
 */
public class InvalidSystemSymbolException
    extends IonException
{
    private static final long serialVersionUID = 2206499395645594047L;
    
    private String myBadSymbol;


    public InvalidSystemSymbolException(String badSymbol)
    {
        super("Invalid system symbol '" + badSymbol + "'");
        myBadSymbol = badSymbol;
    }


    public String getBadSymbol()
    {
        return myBadSymbol;
    }
}
