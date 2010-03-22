/*
 *   $Id$
 *
 *   Copyright 2010 Glencoe Software, Inc. All rights reserved.
 *   Use is subject to license terms supplied in LICENSE.txt
 */
package ome.conditions;

/**
 * Extension of {@link GroupSecurityViolation} signalling that an admin or
 * group owner has tried to make a modification in a private group OR that
 * the member of a read-only group has tried to do the same.
 *
 * @author Josh Moore, josh at glencoesoftware.com
 * @since Beta4.2
 * @see ticket:1769
 * @see ticket:1992
 */
public class ReadOnlyGroupSecurityViolation extends GroupSecurityViolation {

    private static final long serialVersionUID = -45134342129377L;

    public ReadOnlyGroupSecurityViolation(String msg) {
        super(msg);
    }

}
