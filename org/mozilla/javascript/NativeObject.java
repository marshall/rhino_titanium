/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * The contents of this file are subject to the Netscape Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/NPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express oqr
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
 * Mike McCabe
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

import java.util.Hashtable;

/**
 * This class implements the Object native object.
 * See ECMA 15.2.
 * @author Norris Boyd
 */
public class NativeObject extends ScriptableObject {

    public static void finishInit(Scriptable scope, FunctionObject ctor,
                                  Scriptable proto)
    {
        Object obj = proto.get("valueOf", proto);
        ((FunctionObject) obj).setLength((short) 0);
    }

    public String getClassName() {
        return "Object";
    }

    public static Object jsConstructor(Context cx, Object[] args, 
                                       Function ctorObj, boolean inNewExpr)
        throws JavaScriptException
    {
        if (!inNewExpr) {
            // FunctionObject.construct will set up parent, proto
            return ctorObj.construct(cx, ctorObj.getParentScope(), args);
        }
        if (args.length == 0 || args[0] == null ||
            args[0] == Undefined.instance)
        {
            return new NativeObject();
        }
        return ScriptRuntime.toObject(ctorObj.getParentScope(), args[0]);
    }

    public String toString() {
        Context cx = Context.getContext();
        if (cx != null)
            return jsFunction_toString(cx, this, null, null);
        else
            return "[object " + getClassName() + "]";
    }

    public static String jsFunction_toString(Context cx, Scriptable thisObj,
                                             Object[] args, Function funObj)
    {
        if (cx.getLanguageVersion() != cx.VERSION_1_2) 
            return "[object " + thisObj.getClassName() + "]";
        
        return toSource(cx, thisObj, args, funObj);
    }
    
    public static String jsFunction_toLocaleString(Context cx, 
                                                   Scriptable thisObj, 
                                                   Object[] args, 
                                                   Function funObj)
    {
        return jsFunction_toString(cx, thisObj, args, funObj);
    }
    
    public static String toSource(Context cx, Scriptable thisObj,
                                  Object[] args, Function funObj)
    {
        Scriptable m = thisObj;
        
        if (cx.iterating == null)
            cx.iterating = new Hashtable(31);

        if (cx.iterating.get(m) == Boolean.TRUE) {
            return "{}";  // stop recursion
        } else {
            StringBuffer result = new StringBuffer("{");
            Object[] ids = m.getIds();

            for(int i=0; i < ids.length; i++) {
                if (i > 0)
                    result.append(", ");

                Object id = ids[i];
                String idString = ScriptRuntime.toString(id);
                Object p = (id instanceof String)
                    ? m.get((String) id, m)
                    : m.get(((Number) id).intValue(), m);
                if (p instanceof String) {
                    result.append(idString + ":\""
                        + ScriptRuntime
                          .escapeString(ScriptRuntime.toString(p))
                        + "\"");
                } else {
                    /* wrap changes to cx.iterating in a try/finally
                     * so that the reference always gets removed, and
                     * we don't leak memory.  Good place for weak
                     * references, if we had them.
                     */
                    try {
                        cx.iterating.put(m, Boolean.TRUE); // stop recursion.
                        result.append(idString + ":" + ScriptRuntime.toString(p));
                    } finally {
                        cx.iterating.remove(m);
                    }
                }
            }
            result.append("}");
            return result.toString();
        }
    }

    public static Object jsFunction_valueOf(Context cx, Scriptable thisObj,
                                            Object[] args, Function funObj)
    {
        return thisObj;
    }

    public static Object jsFunction_hasOwnProperty(Context cx, 
                                                   Scriptable thisObj,
                                                   Object[] args,
                                                   Function funObj)
    {
        if (args.length != 0)
            if (thisObj.has(ScriptRuntime.toString(args[0]), thisObj))
                return Boolean.TRUE;
        return Boolean.FALSE;
    }

    public static Object jsFunction_propertyIsEnumerable(Context cx,
                                                         Scriptable thisObj,
                                                         Object[] args,
                                                         Function funObj)
    {
        try {
            if (args.length != 0) {
                String name = ScriptRuntime.toString(args[0]);
                if (thisObj.has(name, thisObj)) {
                    int a = ((ScriptableObject)thisObj).getAttributes(name, thisObj);
                    if ((a & ScriptableObject.DONTENUM) == 0)
                        return Boolean.TRUE;
                }
            }
        }
        catch (PropertyException x) {
        }
        catch (ClassCastException x) {
        }
        return Boolean.FALSE;
    }

    public static Object jsFunction_isPrototypeOf(Context cx, Scriptable thisObj,
                                                   Object[] args, Function funObj)
    {
        if (args.length != 0 && args[0] instanceof Scriptable) {
            Scriptable v = (Scriptable) args[0];
            do {
                v = v.getPrototype();
                if (v == thisObj)
                    return Boolean.TRUE;
            } while (v != null);
        }
        return Boolean.FALSE;
    }
}


