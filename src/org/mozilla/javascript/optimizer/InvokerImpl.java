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
 * Copyright (C) 1997-2000 Netscape Communications Corporation. All
 * Rights Reserved.
 *
 * Contributor(s):
 * Norris Boyd
 * David C. Navas
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

package org.mozilla.javascript.optimizer;

import java.util.Hashtable;
import java.lang.reflect.Method;

import org.mozilla.javascript.*;
import org.mozilla.classfile.*;

/**
 * Avoid cost of java.lang.reflect.Method.invoke() by compiling a class to
 * perform the method call directly.
 */
public class InvokerImpl extends Invoker {

    public Invoker createInvoker(ClassCache cache,
                                 Method method, Class[] types)
    {
        Invoker result = (Invoker)invokersCache.get(method);
        if (result != null) { return result; }

        String className = "inv"+cache.newClassSerialNumber();

        ClassFileWriter cfw = new ClassFileWriter(className,
                                "org.mozilla.javascript.Invoker", "");
        cfw.setFlags((short)(ClassFileWriter.ACC_PUBLIC |
                             ClassFileWriter.ACC_FINAL));

        // Add our instantiator!
        cfw.startMethod("<init>", "()V", ClassFileWriter.ACC_PUBLIC);
        cfw.add(ByteCode.ALOAD_0);
        cfw.addInvoke(ByteCode.INVOKESPECIAL,
                      "org.mozilla.javascript.Invoker",
                      "<init>", "()V");
        cfw.add(ByteCode.RETURN);
        cfw.stopMethod((short)1); // one argument -- this???

        // Add the invoke() method call
        cfw.startMethod("invoke",
                        "(Ljava/lang/Object;[Ljava/lang/Object;)"+
                         "Ljava/lang/Object;",
                        (short)(ClassFileWriter.ACC_PUBLIC |
                                ClassFileWriter.ACC_FINAL));

        // If we return a primitive type, then do something special!
        String declaringClassName = method.getDeclaringClass().getName
            ().replace('.', '/');
        Class returnType = method.getReturnType();
        String invokeSpecial = null;
        String invokeSpecialType = null;
        boolean returnsVoid = false;
        boolean returnsBoolean = false;

        if (returnType.isPrimitive()) {
            if (returnType == Boolean.TYPE) {
                returnsBoolean = true;
                invokeSpecialType = "(Z)V";
            } else if (returnType == Void.TYPE) {
                returnsVoid = true;
                invokeSpecialType = "()V";
            } else if (returnType == Integer.TYPE) {
                cfw.add(ByteCode.NEW, invokeSpecial = "java/lang/Integer");
                cfw.add(ByteCode.DUP);
                invokeSpecialType = "(I)V";
            } else if (returnType == Long.TYPE) {
                cfw.add(ByteCode.NEW, invokeSpecial = "java/lang/Long");
                cfw.add(ByteCode.DUP);
                invokeSpecialType = "(J)V";
            } else if (returnType == Short.TYPE) {
                cfw.add(ByteCode.NEW, invokeSpecial = "java/lang/Short");
                cfw.add(ByteCode.DUP);
                invokeSpecialType = "(S)V";
            } else if (returnType == Float.TYPE) {
                cfw.add(ByteCode.NEW, invokeSpecial = "java/lang/Float");
                cfw.add(ByteCode.DUP);
                invokeSpecialType = "(F)V";
            } else if (returnType == Double.TYPE) {
                cfw.add(ByteCode.NEW, invokeSpecial = "java/lang/Double");
                cfw.add(ByteCode.DUP);
                invokeSpecialType = "(D)V";
            } else if (returnType == Byte.TYPE) {
                cfw.add(ByteCode.NEW, invokeSpecial = "java/lang/Byte");
                cfw.add(ByteCode.DUP);
                invokeSpecialType = "(B)V";
            } else if (returnType == Character.TYPE) {
                cfw.add(ByteCode.NEW, invokeSpecial
                            = "java/lang/Character");
                cfw.add(ByteCode.DUP);
                invokeSpecialType = "(C)V";
            }
        }

        // handle setup of call to virtual function (if calling non-static)
        if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            cfw.add(ByteCode.ALOAD_1);
            cfw.add(ByteCode.CHECKCAST, declaringClassName);
        }

        // Handle parameters!
        StringBuffer params = new StringBuffer(2 + ((types!=null)?(20 *
                                types.length):0));

        params.append('(');
        if (types != null) {
            for(int i = 0; i < types.length; i++) {
                Class type = types[i];

                cfw.add(ByteCode.ALOAD_2);

                if (i <= 5) {
                    cfw.add((byte) (ByteCode.ICONST_0 + i));
                } else if (i <= Byte.MAX_VALUE) {
                    cfw.add(ByteCode.BIPUSH, i);
                } else if (i <= Short.MAX_VALUE) {
                    cfw.add(ByteCode.SIPUSH, i);
                } else {
                    cfw.addLoadConstant((int)i);
                }

                cfw.add(ByteCode.AALOAD);

                if (type.isPrimitive()) {
                    // Convert enclosed type back to primitive.

                    if (type == Boolean.TYPE) {
                        cfw.add(ByteCode.CHECKCAST, "java/lang/Boolean");
                        cfw.addInvoke(ByteCode.INVOKEVIRTUAL,
                                      "java/lang/Boolean",
                                      "booleanValue", "()Z");
                        params.append('Z');
                    } else if (type == Integer.TYPE) {
                        cfw.add(ByteCode.CHECKCAST, "java/lang/Number");
                        cfw.addInvoke(ByteCode.INVOKEVIRTUAL,
                                      "java/lang/Number",
                                      "intValue", "()I");
                        params.append('I');
                    } else if (type == Short.TYPE) {
                        cfw.add(ByteCode.CHECKCAST, "java/lang/Number");
                        cfw.addInvoke(ByteCode.INVOKEVIRTUAL,
                                      "java/lang/Number",
                                      "shortValue", "()S");
                        params.append('S');
                    } else if (type == Character.TYPE) {
                        cfw.add(ByteCode.CHECKCAST, "java/lang/Character");
                        cfw.addInvoke(ByteCode.INVOKEVIRTUAL,
                                      "java/lang/Character",
                                      "charValue", "()C");
                        params.append('C');
                    } else if (type == Double.TYPE) {
                        cfw.add(ByteCode.CHECKCAST, "java/lang/Number");
                        cfw.addInvoke(ByteCode.INVOKEVIRTUAL,
                                      "java/lang/Number",
                                      "doubleValue", "()D");
                        params.append('D');
                    } else if (type == Float.TYPE) {
                        cfw.add(ByteCode.CHECKCAST, "java/lang/Number");
                        cfw.addInvoke(ByteCode.INVOKEVIRTUAL,
                                      "java/lang/Number",
                                      "floatValue", "()F");
                        params.append('F');
                    } else if (type == Byte.TYPE) {
                        cfw.add(ByteCode.CHECKCAST, "java/lang/Byte");
                        cfw.addInvoke(ByteCode.INVOKEVIRTUAL,
                                      "java/lang/Byte",
                                      "byteValue", "()B");
                        params.append('B');
                    }
                } else {
                    String typeName = type.getName().replace('.', '/');
                    cfw.add(ByteCode.CHECKCAST, typeName);

                    if (!type.isArray()) {
                        params.append('L');
                    }
                    params.append(typeName);

                    if (!type.isArray()) {
                        params.append(';');
                    }
                }
            }
        }
        params.append(')');
        if (invokeSpecialType != null) {
            if (returnsVoid) {
                params.append('V');
            } else {
                params.append(invokeSpecialType.charAt(1));
            }
        } else if (returnType.isArray()) {
            params.append(returnType.getName().replace('.','/'));
        } else {
            params.append('L');
            params.append(returnType.getName().replace('.','/'));
            params.append(';');
        }

        // Call actual function!
        if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            cfw.addInvoke(ByteCode.INVOKEVIRTUAL,
                          declaringClassName,
                          method.getName(), params.toString());
        } else {
            cfw.addInvoke(ByteCode.INVOKESTATIC, declaringClassName,
                          method.getName(), params.toString());
        }

        // Handle return value
        if (returnsVoid) {
            cfw.add(ByteCode.ACONST_NULL);
            cfw.add(ByteCode.ARETURN);
        } else if (returnsBoolean) {
            // HACK
            //check to see if true;
            // '7' is the number of bytes of the ifeq<branch> plus getstatic<TRUE> plus areturn instructions
            cfw.add(ByteCode.IFEQ, 7);
            cfw.add(ByteCode.GETSTATIC,
                    "java/lang/Boolean",
                    "TRUE",
                    "Ljava/lang/Boolean;");
            cfw.add(ByteCode.ARETURN);
            cfw.add(ByteCode.GETSTATIC,
                    "java/lang/Boolean",
                    "FALSE",
                    "Ljava/lang/Boolean;");
            cfw.add(ByteCode.ARETURN);
        } else if (invokeSpecial != null) {
            cfw.addInvoke(ByteCode.INVOKESPECIAL,
                          invokeSpecial,
                          "<init>", invokeSpecialType);
            cfw.add(ByteCode.ARETURN);
        } else {
            cfw.add(ByteCode.ARETURN);
        }
        cfw.stopMethod((short)3); // three arguments, including the this pointer???

        byte[] bytes = cfw.toByteArray();

        // Add class to our classloader.
        boolean canCache = cache.isCachingEnabled();
        GeneratedClassLoader classLoader;
        synchronized (this) {
            if (canCache && cachedClassLoader != null) {
                classLoader = cachedClassLoader;
            } else {
                Context cx = Context.getCurrentContext();
                ClassLoader parentLoader = cx.getApplicationClassLoader();
                classLoader = cx.createClassLoader(parentLoader);
                if (canCache) {
                    cachedClassLoader = classLoader;
                }
            }
        }

        Class c = classLoader.defineClass(className, bytes);
        classLoader.linkClass(c);
        try {
            result = (Invoker)c.newInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException("unexpected " + e.toString());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("unexpected " + e.toString());
        }
        if (false) {
            System.out.println
                ("Generated method delegate for: "+method.getName()
                 +" on "+method.getDeclaringClass().getName()+" :: "
                 +params.toString()+" :: "+types);
        }
        if (canCache) {
            invokersCache.put(method, result);
        }
        return result;
    }

    public void clearMasterCaches()
    {
        synchronized (this) {
            cachedClassLoader = null;
        }
        invokersCache = new Hashtable();
    }

    public Object invoke(Object that, Object [] args) {
        return null;
    }

    private Hashtable invokersCache = new Hashtable();
    private GeneratedClassLoader cachedClassLoader;
}
