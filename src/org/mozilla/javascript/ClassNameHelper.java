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
 * Roger Lawrence
 * Andi Vajda
 * Kemal Bayram
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

package org.mozilla.javascript;

import java.io.*;

/**
 * @deprectated To generate class files from script sources, use
 * {@link org.mozilla.javascript.optimizer.ClassCompiler}.
 */

public abstract class ClassNameHelper {

    public static ClassNameHelper get(Context cx) {
        ClassNameHelper helper = savedNameHelper;
        if (helper == null && !helperNotAvailable) {
            Class nameHelperClass = Kit.classOrNull(
                "org.mozilla.javascript.optimizer.OptClassNameHelper");
            // nameHelperClass == null if running lite
            if (nameHelperClass != null) {
                helper = (ClassNameHelper)Kit.newInstanceOrNull(
                                              nameHelperClass);
            }
            if (helper != null) {
                savedNameHelper = helper;
            } else {
                helperNotAvailable = true;
            }
        }
        return helper;
    }

    public abstract String getTargetClassFileName();

    public abstract void setTargetClassFileName(String classFileName);

    public abstract String getTargetPackage();

    public abstract void setTargetPackage(String targetPackage);

    public abstract void setTargetExtends(Class extendsClass);

    public abstract void setTargetImplements(Class[] implementsClasses);

    public abstract ClassRepository getClassRepository();

    public abstract void setClassRepository(ClassRepository repository);

    public abstract String getClassName();

    public abstract void setClassName(String initialName);

    private static ClassNameHelper savedNameHelper;
    private static boolean helperNotAvailable;
}
