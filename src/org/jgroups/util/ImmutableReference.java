/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc. and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jgroups.util;

/**
 * Simple class that holds an immutable reference to another object (or to
 * <code>null</code>).
 *
 * @author Brian Stansberry
 * 
 */
public class ImmutableReference<T> {

    private final T referent;
    
    /** 
     * Create a new ImmutableReference.
     * 
     * @param referent the object to refer to, or <code>null</code>
     */
    public ImmutableReference(T referent) {
        this.referent = referent;
    }
    
    /**
     * Gets the wrapped object, if there is one.
     * 
     * @return the object passed to the constructor, or <code>null</code> if
     *         <code>null</code> was passed to the constructor
     */
    public T get() {
        return referent;
    }
}
