/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * The contents of this file are subject to the Netscape Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/NPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * The Original Code is Rhino code, released
 * May 6, 1999.
 *
 * The Initial Developer of the Original Code is Netscape
 * Communications Corporation.  Portions created by Netscape are
 * Copyright (C) 1997-1999 Netscape Communications Corporation. All
 * Rights Reserved.
 *
 * Contributor(s):
 * Norris Boyd
 * Igor Bukanov
 *
 * Alternatively, the contents of this file may be used under the
 * terms of the GNU Public License (the "GPL"), in which case the
 * provisions of the GPL are applicable instead of those above.
 * If you wish to allow use of your version of this file only
 * under the terms of the GPL and not to allow others to use your
 * version of this file under the NPL, indicate your decision by
 * deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL.  If you do not delete
 * the provisions above, a recipient may use your version of this
 * file under either the NPL or the GPL.
 */

// API class

package org.mozilla.javascript;

/**
 * This class describes the support needed to implement security.
 * <p>
 * Three main pieces of functionality are required to implement
 * security for JavaScript. First, it must be possible to define
 * classes with an associated security domain. (This security
 * domain may be any object incorporating notion of access
 * restrictions that has meaning to an embedding; for a client-side
 * JavaScript embedding this would typically be
 * java.security.ProtectionDomain or similar object depending on an
 * origin URL and/or a digital certificate.)
 * Next it must be possible to get a security domain object that
 * allows a particular action only if all security domains
 * associated with code on the current Java stack allows it. And
 * finally, it must be possible to execute script code with
 * associated security domain injected into Java stack.
 * <p>
 * These three pieces of functionality are encapsulated in the
 * SecurityController class.
 *
 * @see org.mozilla.javascript.Context#setSecurityController(SecurityController)
 * @see java.lang.ClassLoader
 * @since 1.5 Release 4
 */
public abstract class SecurityController {

    /**
     * Get class loader-like object that can be used
     * to define classes with the given security context.
     * @param parentLoader parent class loader to delegate search for classes
     *        not defined by the class loader itself
     * @param securityDomain some object specifying the security
     *        context of the code that is defined by the returned class loader.
     */
    public abstract GeneratedClassLoader
    createClassLoader(ClassLoader parentLoader, Object securityDomain);

    /**
     * Get dynamic security domain that allows an action only if it is allowed
     * by the current Java stack and <i>securityDomain</i>. If
     * <i>securityDomain</i> is null, return domain representing permissions
     * allowed by the current stack.
     */
    public abstract Object getDynamicSecurityDomain(Object securityDomain);

    /**
     * Call {@link Script#exec(Context cx, Scriptable scope)} of
     * <i>script</i> under restricted security domain where an action is
     * allowed only if it is allowed according to the Java stack on the
     * moment of the <i>execWithDomain</i> call and <i>securityDomain</i>.
     * Any call to {@link #getDynamicSecurityDomain(Object)} during
     * execution of {@link Script#exec(Context cx, Scriptable scope)}
     * should return a domain incorporate restrictions imposed by
     * <i>securityDomain</i>.
     */
    public abstract Object execWithDomain(Context cx, Scriptable scope,
                                          Script script, Object securityDomain)
        throws JavaScriptException;
}
