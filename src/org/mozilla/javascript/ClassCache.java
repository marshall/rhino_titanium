/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*- */

/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is cache holder of generated code for Java reflection
 *
 * The Initial Developer of the Original Code is
 * RUnit Software AS.
 * Portions created by the Initial Developer are Copyright (C) 2003
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s): Igor Bukanov, igor@fastmail.fm
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.mozilla.javascript;

import java.io.*;
import java.util.Hashtable;

/**
 * Cache of generated classes and data structures to access Java runtime
 * from JavaScript.
 *
 * @author Igor Bukanov
 *
 * @since Rhino 1.5 Release 5
 */
public class ClassCache
{
    private static final Object AKEY = new Object();

    private volatile boolean cachingIsEnabled = true;

    Hashtable classTable = new Hashtable();

    boolean invokerOptimization = true;
    Invoker invokerMaster;

    Hashtable javaAdapterGeneratedClasses = new Hashtable();

    Hashtable javaAdapterIFGlueMasters = new Hashtable();

    private int generatedClassSerial;

    /**
     * Search for ClassCache object in the given scope.
     * The method first calls
     * {@link ScriptableObject#getTopLevelScope(Scriptable scope)}
     * to get the top most scope and then tries to locate associated
     * ClassCache object in the prototype chain of the top scope.
     *
     * @param scope scope to search for ClassCache object.
     * @return previously associated ClassCache object or a new instance of
     *         ClassCache if no ClassCache object was found.
     *
     * @see #associate(ScriptableObject topScope)
     */
    public static ClassCache get(Scriptable scope)
    {
        scope = ScriptableObject.getTopLevelScope(scope);
        Scriptable obj = scope;
        do {
            if (obj instanceof ScriptableObject) {
                ScriptableObject so = (ScriptableObject)obj;
                ClassCache lc = (ClassCache)so.getAssociatedValue(AKEY);
                if (lc != null) {
                    return lc;
                }
            }
            obj = obj.getPrototype();
        } while (obj != null);

        // ALERT: warn somehow about wrong cache usage ?
        return new ClassCache();
    }

    /**
     * Associate ClassCache object with the given top-level scope.
     * The ClassCache object can only be associated with the given scope once.
     *
     * @param topScope scope to associate this ClassCache object with.
     * @return true if no prevous ClassCache objects were embedded into
     *         the scope and this ClassCache were successfully associated
     *         or false otherwise.
     *
     * @see #get(Scriptable scope)
     */
    public boolean associate(ScriptableObject topScope)
    {
        if (topScope.getParentScope() != null) {
            // Can only associate cache with top level scope
            throw new IllegalArgumentException();
        }
        return this != topScope.associateValue(AKEY, this);
    }

    /**
     * Empty caches of generated Java classes and Java reflection information.
     */
    public synchronized void clearCaches()
    {
        classTable = new Hashtable();
        javaAdapterGeneratedClasses = new Hashtable();
        javaAdapterIFGlueMasters = new Hashtable();
        Invoker im = invokerMaster;
        if (im != null) {
            im.clearMasterCaches();
        }
    }

    /**
     * Check if generated Java classes and Java reflection information
     * is cached.
     */
    public final boolean isCachingEnabled()
    {
        return cachingIsEnabled;
    }

     /**
     * Set whether to cache some values.
     * <p>
     * By default, the engine will cache generated classes and
     * results of <tt>Class.getMethods()</tt> and similar calls.
     * This can speed execution dramatically, but increases the memory
     * footprint. Also, with caching enabled, references may be held to
     * objects past the lifetime of any real usage.
     * <p>
     * If caching is enabled and this method is called with a
     * <code>false</code> argument, the caches will be emptied.
     * <p>
     * Caching is enabled by default.
     *
     * @param enabled if true, caching is enabled
     *
     * @see #clearCaches()
     */
    public synchronized void setCachingEnabled(boolean enabled)
    {
        if (enabled == cachingIsEnabled)
            return;
        if (!enabled)
            clearCaches();
        cachingIsEnabled = enabled;
    }

    /**
     * To optimize invocation of reflected Java methods, the engine generates
     * special glue classes that will call the methods directly. By default
     * the optimization is enabled since it allows to speedup method invocation
     * compared with calling <tt>Method.invoke</tt> by factor 2-2.5 under JDK
     * 1.4.2 and by factor 10-15 under JDK 1.3.1. If increase memory
     * consumption is too high or the optimization brings no benefits in a
     * particular VM, then the optimization can be disabled.
     *
     * @param enabled if true, invoke optimization is enabled.
     */
    public synchronized void setInvokerOptimizationEnabled(boolean enabled)
    {
        if (invokerOptimization == enabled)
            return;
        if (!enabled)
            invokerMaster = null;
        invokerOptimization = enabled;
    }

    /**
     * Internal engine method to return serial number for generated classes
     * to ensure name uniqueness.
     */
    public final synchronized int newClassSerialNumber()
    {
        return ++generatedClassSerial;
    }
}
