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
 * Igor Bukanov
 * Roger Lawrence
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

import java.lang.reflect.*;
import java.util.Hashtable;

/**
 * This is the default implementation of the Scriptable interface. This
 * class provides convenient default behavior that makes it easier to
 * define host objects.
 * <p>
 * Various properties and methods of JavaScript objects can be conveniently
 * defined using methods of ScriptableObject.
 * <p>
 * Classes extending ScriptableObject must define the getClassName method.
 *
 * @see org.mozilla.javascript.Scriptable
 * @author Norris Boyd
 */

public abstract class ScriptableObject implements Scriptable {

    /**
     * The empty property attribute.
     *
     * Used by getAttributes() and setAttributes().
     *
     * @see org.mozilla.javascript.ScriptableObject#getAttributes
     * @see org.mozilla.javascript.ScriptableObject#setAttributes
     */
    public static final int EMPTY =     0x00;

    /**
     * Property attribute indicating assignment to this property is ignored.
     *
     * @see org.mozilla.javascript.ScriptableObject#put
     * @see org.mozilla.javascript.ScriptableObject#getAttributes
     * @see org.mozilla.javascript.ScriptableObject#setAttributes
     */
    public static final int READONLY =  0x01;

    /**
     * Property attribute indicating property is not enumerated.
     *
     * Only enumerated properties will be returned by getIds().
     *
     * @see org.mozilla.javascript.ScriptableObject#getIds
     * @see org.mozilla.javascript.ScriptableObject#getAttributes
     * @see org.mozilla.javascript.ScriptableObject#setAttributes
     */
    public static final int DONTENUM =  0x02;

    /**
     * Property attribute indicating property cannot be deleted.
     *
     * @see org.mozilla.javascript.ScriptableObject#delete
     * @see org.mozilla.javascript.ScriptableObject#getAttributes
     * @see org.mozilla.javascript.ScriptableObject#setAttributes
     */
    public static final int PERMANENT = 0x04;

    /**
     * Return the name of the class.
     *
     * This is typically the same name as the constructor.
     * Classes extending ScriptableObject must implement this abstract
     * method.
     */
    public abstract String getClassName();

    /**
     * Returns true if the named property is defined.
     *
     * @param name the name of the property
     * @param start the object in which the lookup began
     * @return true if and only if the property was found in the object
     */
    public boolean has(String name, Scriptable start) {
        return getSlot(name, name.hashCode(), false) != null;
    }

    /**
     * Returns true if the property index is defined.
     *
     * @param index the numeric index for the property
     * @param start the object in which the lookup began
     * @return true if and only if the property was found in the object
     */
    public boolean has(int index, Scriptable start) {
        return getSlot(null, index, false) != null;
    }

    /**
     * Returns the value of the named property or NOT_FOUND.
     *
     * If the property was created using defineProperty, the
     * appropriate getter method is called.
     *
     * @param name the name of the property
     * @param start the object in which the lookup began
     * @return the value of the property (may be null), or NOT_FOUND
     */
    public Object get(String name, Scriptable start) {
        Slot slot = lastAccess; // Get local copy
        if (name == slot.stringKey) {
            if (slot.wasDeleted == 0) { return slot.value; }
        } 
        int hashCode = name.hashCode();
        slot = getSlot(name, hashCode, false);
        if (slot == null)
            return Scriptable.NOT_FOUND;
        if ((slot.flags & Slot.HAS_GETTER) != 0) {
            GetterSlot getterSlot = (GetterSlot) slot;
            try {
                if (getterSlot.delegateTo == null) {
                    // Walk the prototype chain to find an appropriate
                    // object to invoke the getter on.
                    Class clazz = getterSlot.getter.getDeclaringClass();
                    while (!clazz.isInstance(start)) {
                        start = start.getPrototype();
                        if (start == null) {
                            start = this;
                            break;
                        }
                    }
                    return getterSlot.getter.invoke(start, ScriptRuntime.emptyArgs);
                }
                Object[] args = { this };
                return getterSlot.getter.invoke(getterSlot.delegateTo, args);
            }
            catch (InvocationTargetException e) {
                throw WrappedException.wrapException(e);
            }
            catch (IllegalAccessException e) {
                throw WrappedException.wrapException(e);
            }
        }
        // Here stringKey.equals(name) holds, but it can be that 
        // slot.stringKey != name. To make last name cache work, need
        // to change the key
        slot.stringKey = name;

        // Update cache. 
        lastAccess = slot;
        return slot.value;
    }

    /**
     * Returns the value of the indexed property or NOT_FOUND.
     *
     * @param index the numeric index for the property
     * @param start the object in which the lookup began
     * @return the value of the property (may be null), or NOT_FOUND
     */
    public Object get(int index, Scriptable start) {
        Slot slot = getSlot(null, index, false);
        if (slot == null)
            return Scriptable.NOT_FOUND;
        return slot.value;
    }
    
    /**
     * Sets the value of the named property, creating it if need be.
     *
     * If the property was created using defineProperty, the
     * appropriate setter method is called. <p>
     *
     * If the property's attributes include READONLY, no action is
     * taken.
     * This method will actually set the property in the start
     * object.
     *
     * @param name the name of the property
     * @param start the object whose property is being set
     * @param value value to set the property to
     */
    public void put(String name, Scriptable start, Object value) {
        int hash = name.hashCode();
        Slot slot = getSlot(name, hash, false);
        if (slot == null) {
            if (start != this) {
                start.put(name, start, value);
                return;
            }
            slot = getSlotToSet(name, hash, false);
        }
        if ((slot.attributes & ScriptableObject.READONLY) != 0)
            return;
        if ((slot.flags & Slot.HAS_SETTER) != 0) {
            GetterSlot getterSlot = (GetterSlot) slot;
            try {
                Class pTypes[] = getterSlot.setter.getParameterTypes();
                Class desired = pTypes[pTypes.length - 1];
                Object actualArg
                        = FunctionObject.convertArg(start, value, desired);
                if (getterSlot.delegateTo == null) {
                    // Walk the prototype chain to find an appropriate
                    // object to invoke the setter on.
                    Object[] arg = { actualArg };
                    Class clazz = getterSlot.setter.getDeclaringClass();
                    while (!clazz.isInstance(start)) {
                        start = start.getPrototype();
                        if (start == null) {
                            start = this;
                            break;
                        }
                    }
                    Object v = getterSlot.setter.invoke(start, arg);
                    if (getterSlot.setterReturnsValue) {
                        slot.value = v;
                        if (!(v instanceof Method))
                            slot.flags = 0;
                    }
                    return;
                }
                Object[] args = { this, actualArg };
                Object v = getterSlot.setter.invoke(getterSlot.delegateTo, args);
                if (getterSlot.setterReturnsValue) {
                    slot.value = v;
                    if (!(v instanceof Method))
                        slot.flags = 0;
                }
                return;
            }
            catch (InvocationTargetException e) {
                throw WrappedException.wrapException(e);
            }
            catch (IllegalAccessException e) {
                throw WrappedException.wrapException(e);
            }
        }
        if (this == start) {
            slot.value = value;
            // Make cache work
            slot.stringKey = name;
            lastAccess = slot;
        } else {
            start.put(name, start, value);
        }
    }

    /**
     * Sets the value of the indexed property, creating it if need be.
     *
     * @param index the numeric index for the property
     * @param start the object whose property is being set
     * @param value value to set the property to
     */
    public void put(int index, Scriptable start, Object value) {
        Slot slot = getSlot(null, index, false);
        if (slot == null) {
            if (start != this) {
                start.put(index, start, value);
                return;
            }
            slot = getSlotToSet(null, index, false);
        }
        if ((slot.attributes & ScriptableObject.READONLY) != 0)
            return;
        if (this == start) {
            slot.value = value;
        } else {
            start.put(index, start, value);
        }
    }

    /**
     * Removes a named property from the object.
     *
     * If the property is not found, or it has the PERMANENT attribute,
     * no action is taken.
     *
     * @param name the name of the property
     */
    public void delete(String name) {
        removeSlot(name, name.hashCode());
    }

    /**
     * Removes the indexed property from the object.
     *
     * If the property is not found, or it has the PERMANENT attribute,
     * no action is taken.
     *
     * @param index the numeric index for the property
     */
    public void delete(int index) {
        removeSlot(null, index);
    }

    /**
     * Get the attributes of a named property.
     *
     * The property is specified by <code>name</code>
     * as defined for <code>has</code>.<p>
     *
     * @param name the identifier for the property
     * @param start the object in which the lookup began
     * @return the bitset of attributes
     * @exception PropertyException if the named property
     *            is not found
     * @see org.mozilla.javascript.ScriptableObject#has
     * @see org.mozilla.javascript.ScriptableObject#READONLY
     * @see org.mozilla.javascript.ScriptableObject#DONTENUM
     * @see org.mozilla.javascript.ScriptableObject#PERMANENT
     * @see org.mozilla.javascript.ScriptableObject#EMPTY
     */
    public int getAttributes(String name, Scriptable start)
        throws PropertyException
    {
        Slot slot = getSlot(name, name.hashCode(), false);
        if (slot == null) {
            throw PropertyException.withMessage0("msg.prop.not.found");
        }
        return slot.attributes;
    }

    /**
     * Get the attributes of an indexed property.
     *
     * @param index the numeric index for the property
     * @param start the object in which the lookup began
     * @exception PropertyException if the indexed property
     *            is not found
     * @return the bitset of attributes
     * @see org.mozilla.javascript.ScriptableObject#has
     * @see org.mozilla.javascript.ScriptableObject#READONLY
     * @see org.mozilla.javascript.ScriptableObject#DONTENUM
     * @see org.mozilla.javascript.ScriptableObject#PERMANENT
     * @see org.mozilla.javascript.ScriptableObject#EMPTY
     */
    public int getAttributes(int index, Scriptable start)
        throws PropertyException
    {
        Slot slot = getSlot(null, index, false);
        if (slot == null) {
            throw PropertyException.withMessage0("msg.prop.not.found");
        }
        return slot.attributes;
    }

    /**
     * Set the attributes of a named property.
     *
     * The property is specified by <code>name</code>
     * as defined for <code>has</code>.<p>
     *
     * The possible attributes are READONLY, DONTENUM,
     * and PERMANENT. Combinations of attributes
     * are expressed by the bitwise OR of attributes.
     * EMPTY is the state of no attributes set. Any unused
     * bits are reserved for future use.
     *
     * @param name the name of the property
     * @param start the object in which the lookup began
     * @param attributes the bitset of attributes
     * @exception PropertyException if the named property
     *            is not found
     * @see org.mozilla.javascript.Scriptable#has
     * @see org.mozilla.javascript.ScriptableObject#READONLY
     * @see org.mozilla.javascript.ScriptableObject#DONTENUM
     * @see org.mozilla.javascript.ScriptableObject#PERMANENT
     * @see org.mozilla.javascript.ScriptableObject#EMPTY
     */
    public void setAttributes(String name, Scriptable start,
                              int attributes)
        throws PropertyException
    {
        final int mask = READONLY | DONTENUM | PERMANENT;
        attributes &= mask; // mask out unused bits
        Slot slot = getSlot(name, name.hashCode(), false);
        if (slot == null) {
            throw PropertyException.withMessage0("msg.prop.not.found");
        }
        slot.attributes = (short) attributes;
    }

    /**
     * Set the attributes of an indexed property.
     *
     * @param index the numeric index for the property
     * @param start the object in which the lookup began
     * @param attributes the bitset of attributes
     * @exception PropertyException if the indexed property
     *            is not found
     * @see org.mozilla.javascript.Scriptable#has
     * @see org.mozilla.javascript.ScriptableObject#READONLY
     * @see org.mozilla.javascript.ScriptableObject#DONTENUM
     * @see org.mozilla.javascript.ScriptableObject#PERMANENT
     * @see org.mozilla.javascript.ScriptableObject#EMPTY
     */
    public void setAttributes(int index, Scriptable start,
                              int attributes)
        throws PropertyException
    {
        Slot slot = getSlot(null, index, false);
        if (slot == null) {
            throw PropertyException.withMessage0("msg.prop.not.found");
        }
        slot.attributes = (short) attributes;
    }

    /**
     * Returns the prototype of the object.
     */
    public Scriptable getPrototype() {
        return prototype;
    }

    /**
     * Sets the prototype of the object.
     */
    public void setPrototype(Scriptable m) {
        prototype = m;
    }

    /**
     * Returns the parent (enclosing) scope of the object.
     */
    public Scriptable getParentScope() {
        return parent;
    }

    /**
     * Sets the parent (enclosing) scope of the object.
     */
    public void setParentScope(Scriptable m) {
        parent = m;
    }

    /**
     * Returns an array of ids for the properties of the object.
     *
     * <p>Any properties with the attribute DONTENUM are not listed. <p>
     *
     * @return an array of java.lang.Objects with an entry for every
     * listed property. Properties accessed via an integer index will 
     * have a corresponding
     * Integer entry in the returned array. Properties accessed by
     * a String will have a String entry in the returned array.
     */
    public Object[] getIds() {
        return getIds(false);
    }
    
    /**
     * Returns an array of ids for the properties of the object.
     *
     * <p>All properties, even those with attribute DONTENUM, are listed. <p>
     *
     * @return an array of java.lang.Objects with an entry for every
     * listed property. Properties accessed via an integer index will 
     * have a corresponding
     * Integer entry in the returned array. Properties accessed by
     * a String will have a String entry in the returned array.
     */
    public Object[] getAllIds() {
        return getIds(true);
    }
    
    /**
     * Implements the [[DefaultValue]] internal method.
     *
     * <p>Note that the toPrimitive conversion is a no-op for
     * every type other than Object, for which [[DefaultValue]]
     * is called. See ECMA 9.1.<p>
     *
     * A <code>hint</code> of null means "no hint".
     *
     * @param typeHint the type hint
     * @return the default value for the object
     *
     * See ECMA 8.6.2.6.
     */
    public Object getDefaultValue(Class typeHint) {
        Object val;
        Context cx = null;
        try {
            for (int i=0; i < 2; i++) {
                if (typeHint == ScriptRuntime.StringClass ? i == 0 : i == 1) {
                    Object v = getProperty(this, "toString");
                    if (!(v instanceof Function))
                        continue;
                    Function fun = (Function) v;
                    if (cx == null)
                        cx = Context.getContext();
                    val = fun.call(cx, fun.getParentScope(), this,
                                   ScriptRuntime.emptyArgs);
                } else {
                    String hint;
                    if (typeHint == null)
                        hint = "undefined";
                    else if (typeHint == ScriptRuntime.StringClass)
                        hint = "string";
                    else if (typeHint == ScriptRuntime.ScriptableClass)
                        hint = "object";
                    else if (typeHint == ScriptRuntime.FunctionClass)
                        hint = "function";
                    else if (typeHint == ScriptRuntime.BooleanClass || 
                                                typeHint == Boolean.TYPE)
                        hint = "boolean";
                    else if (typeHint == ScriptRuntime.NumberClass ||
                             typeHint == ScriptRuntime.ByteClass || 
                             typeHint == Byte.TYPE ||
                             typeHint == ScriptRuntime.ShortClass || 
                             typeHint == Short.TYPE ||
                             typeHint == ScriptRuntime.IntegerClass || 
                             typeHint == Integer.TYPE ||
                             typeHint == ScriptRuntime.FloatClass || 
                             typeHint == Float.TYPE ||
                             typeHint == ScriptRuntime.DoubleClass || 
                             typeHint == Double.TYPE)
                        hint = "number";
                    else {
                        throw Context.reportRuntimeError1(
                            "msg.invalid.type", typeHint.toString());
                    }
                    Object v = getProperty(this, "valueOf");
                    if (!(v instanceof Function))
                        continue;
                    Function fun = (Function) v;
                    Object[] args = { hint };
                    if (cx == null)
                        cx = Context.getContext();
                    val = fun.call(cx, fun.getParentScope(), this, args);
                }
                if (val != null && (val == Undefined.instance ||
                                    !(val instanceof Scriptable) ||
                                    typeHint == Scriptable.class ||
                                    typeHint == Function.class))
                {
                    return val;
                }
                if (val instanceof NativeJavaObject) {
                    // Let a wrapped java.lang.String pass for a primitive 
                    // string.
                    Object u = ((Wrapper) val).unwrap();
                    if (u instanceof String)
                        return u;
                }
            }
            // fall through to error 
        }
        catch (JavaScriptException jse) {
            // fall through to error 
        }
        Object arg = (typeHint == null) ? "undefined" : typeHint.toString();
        throw NativeGlobal.typeError1("msg.default.value", arg, this);
    }

    /**
     * Implements the instanceof operator.
     *
     * <p>This operator has been proposed to ECMA.
     *
     * @param instance The value that appeared on the LHS of the instanceof
     *              operator
     * @return true if "this" appears in value's prototype chain
     *
     */
    public boolean hasInstance(Scriptable instance) {
        // Default for JS objects (other than Function) is to do prototype
        // chasing.  This will be overridden in NativeFunction and non-JS objects.

        return ScriptRuntime.jsDelegatesTo(instance, this);
    }

    /**
     * Defines JavaScript objects from a Java class that implements Scriptable.
     *
     * If the given class has a method
     * <pre>
     * static void init(Context cx, Scriptable scope, boolean sealed);</pre>
     *
     * or its compatibility form 
     * <pre>
     * static void init(Scriptable scope);</pre>
     *
     * then it is invoked and no further initialization is done.<p>
     *
     * However, if no such a method is found, then the class's constructors and
     * methods are used to initialize a class in the following manner.<p>
     *
     * First, the zero-parameter constructor of the class is called to
     * create the prototype. If no such constructor exists,
     * a ClassDefinitionException is thrown. <p>
     *
     * Next, all methods are scanned for special prefixes that indicate that they
     * have special meaning for defining JavaScript objects.
     * These special prefixes are
     * <ul>
     * <li><code>jsFunction_</code> for a JavaScript function
     * <li><code>jsStaticFunction_</code> for a JavaScript function that 
     *           is a property of the constructor
     * <li><code>jsGet_</code> for a getter of a JavaScript property
     * <li><code>jsSet_</code> for a setter of a JavaScript property
     * <li><code>jsConstructor</code> for a JavaScript function that 
     *           is the constructor
     * </ul><p>
     *
     * If the method's name begins with "jsFunction_", a JavaScript function 
     * is created with a name formed from the rest of the Java method name 
     * following "jsFunction_". So a Java method named "jsFunction_foo" will
     * define a JavaScript method "foo". Calling this JavaScript function 
     * will cause the Java method to be called. The parameters of the method
     * must be of number and types as defined by the FunctionObject class.
     * The JavaScript function is then added as a property
     * of the prototype. <p>
     * 
     * If the method's name begins with "jsStaticFunction_", it is handled
     * similarly except that the resulting JavaScript function is added as a 
     * property of the constructor object. The Java method must be static.
     * 
     * If the method's name begins with "jsGet_" or "jsSet_", the method is
     * considered to define a property. Accesses to the defined property
     * will result in calls to these getter and setter methods. If no
     * setter is defined, the property is defined as READONLY.<p>
     *
     * If the method's name is "jsConstructor", the method is
     * considered to define the body of the constructor. Only one 
     * method of this name may be defined. 
     * If no method is found that can serve as constructor, a Java
     * constructor will be selected to serve as the JavaScript
     * constructor in the following manner. If the class has only one
     * Java constructor, that constructor is used to define
     * the JavaScript constructor. If the the class has two constructors,
     * one must be the zero-argument constructor (otherwise an
     * ClassDefinitionException would have already been thrown
     * when the prototype was to be created). In this case
     * the Java constructor with one or more parameters will be used
     * to define the JavaScript constructor. If the class has three
     * or more constructors, an ClassDefinitionException
     * will be thrown.<p>
     *
     * Finally, if there is a method
     * <pre>
     * static void finishInit(Scriptable scope, FunctionObject constructor,
     *                        Scriptable prototype)</pre>
     *
     * it will be called to finish any initialization. The <code>scope</code>
     * argument will be passed, along with the newly created constructor and
     * the newly created prototype.<p>
     *
     * @param scope The scope in which to define the constructor
     * @param clazz The Java class to use to define the JavaScript objects
     *              and properties
     * @exception IllegalAccessException if access is not available
     *            to a reflected class member
     * @exception InstantiationException if unable to instantiate
     *            the named class
     * @exception InvocationTargetException if an exception is thrown
     *            during execution of methods of the named class
     * @exception ClassDefinitionException if an appropriate
     *            constructor cannot be found to create the prototype
     * @exception PropertyException if getter and setter
     *            methods do not conform to the requirements of the
     *            defineProperty method
     * @see org.mozilla.javascript.Function
     * @see org.mozilla.javascript.FunctionObject
     * @see org.mozilla.javascript.ScriptableObject#READONLY
     * @see org.mozilla.javascript.ScriptableObject#defineProperty
     */
    public static void defineClass(Scriptable scope, Class clazz)
        throws IllegalAccessException, InstantiationException,
               InvocationTargetException, ClassDefinitionException,
               PropertyException
    {
        defineClass(scope, clazz, false);
    }
    
    /**
     * Defines JavaScript objects from a Java class, optionally 
     * allowing sealing.
     *
     * Similar to <code>defineClass(Scriptable scope, Class clazz)</code>
     * except that sealing is allowed. An object that is sealed cannot have 
     * properties added or removed. Note that sealing is not allowed in
     * the current ECMA/ISO language specification, but is likely for
     * the next version.
     * 
     * @param scope The scope in which to define the constructor
     * @param clazz The Java class to use to define the JavaScript objects
     *              and properties. The class must implement Scriptable.
     * @param sealed whether or not to create sealed standard objects that
     *               cannot be modified. 
     * @exception IllegalAccessException if access is not available
     *            to a reflected class member
     * @exception InstantiationException if unable to instantiate
     *            the named class
     * @exception InvocationTargetException if an exception is thrown
     *            during execution of methods of the named class
     * @exception ClassDefinitionException if an appropriate
     *            constructor cannot be found to create the prototype
     * @exception PropertyException if getter and setter
     *            methods do not conform to the requirements of the
     *            defineProperty method
     * @since 1.4R3
     */
    public static void defineClass(Scriptable scope, Class clazz, 
                                   boolean sealed)
        throws IllegalAccessException, InstantiationException,
               InvocationTargetException, ClassDefinitionException,
               PropertyException
    {
        Method[] methods = FunctionObject.getMethodList(clazz);
        for (int i=0; i < methods.length; i++) {
            Method method = methods[i];
            if (!method.getName().equals("init"))
                continue;
            Class[] parmTypes = method.getParameterTypes();
            if (parmTypes.length == 3 &&
                parmTypes[0] == ContextClass &&
                parmTypes[1] == ScriptRuntime.ScriptableClass &&
                parmTypes[2] == Boolean.TYPE &&
                Modifier.isStatic(method.getModifiers()))
            {
                Object args[] = { Context.getContext(), scope, 
                                  sealed ? Boolean.TRUE : Boolean.FALSE };
                method.invoke(null, args);
                return;
            }
            if (parmTypes.length == 1 &&
                parmTypes[0] == ScriptRuntime.ScriptableClass &&
                Modifier.isStatic(method.getModifiers()))
            {
                Object args[] = { scope };
                method.invoke(null, args);
                return;
            }
            
        }

        // If we got here, there isn't an "init" method with the right
        // parameter types.
        Hashtable exclusionList = getExclusionList();

        Constructor[] ctors = clazz.getConstructors();
        Constructor protoCtor = null;
        for (int i=0; i < ctors.length; i++) {
            if (ctors[i].getParameterTypes().length == 0) {
                protoCtor = ctors[i];
                break;
            }
        }
        if (protoCtor == null) {
            throw new ClassDefinitionException(
                    Context.getMessage1("msg.zero.arg.ctor", clazz.getName()));
        }

        Scriptable proto = (Scriptable) 
                        protoCtor.newInstance(ScriptRuntime.emptyArgs);
        proto.setPrototype(getObjectPrototype(scope));
        String className = proto.getClassName();

        // Find out whether there are any methods that begin with
        // "js". If so, then only methods that begin with special
        // prefixes will be defined as JavaScript entities.
        // The prefixes "js_" and "jsProperty_" are deprecated.
        final String genericPrefix = "js_";
        final String functionPrefix = "jsFunction_";
        final String staticFunctionPrefix = "jsStaticFunction_";
        final String propertyPrefix = "jsProperty_";
        final String getterPrefix = "jsGet_";
        final String setterPrefix = "jsSet_";
        final String ctorName = "jsConstructor";

        boolean hasPrefix = false;
        Method[] ctorMeths = FunctionObject.findMethods(clazz, ctorName);
        Member ctorMember = null;
        if (ctorMeths != null) {
            if (ctorMeths.length > 1) {
                throw new ClassDefinitionException(
                    Context.getMessage2("msg.multiple.ctors", 
                                        ctorMeths[0], ctorMeths[1]));
            }
            ctorMember = ctorMeths[0];
            hasPrefix = true;
        }

        // Deprecated: look for functions with the same name as the class
        // and consider them constructors.
        for (int i=0; i < methods.length; i++) {
            String name = methods[i].getName();
            String prefix = null;
            if (!name.startsWith("js")) // common start to all prefixes
                prefix = null;
            else if (name.startsWith(genericPrefix))
                prefix = genericPrefix;
            else if (name.startsWith(functionPrefix))
                prefix = functionPrefix;
            else if (name.startsWith(staticFunctionPrefix))
                prefix = staticFunctionPrefix;
            else if (name.startsWith(propertyPrefix))
                prefix = propertyPrefix;
            else if (name.startsWith(getterPrefix))
                prefix = getterPrefix;
            else if (name.startsWith(setterPrefix))
                prefix = setterPrefix;
            if (prefix != null) {
                hasPrefix = true;
                name = name.substring(prefix.length());
            }
            if (name.equals(className)) {
                if (ctorMember != null) {
                    throw new ClassDefinitionException(
                        Context.getMessage2("msg.multiple.ctors", 
                                            ctorMember, methods[i]));
                }
                ctorMember = methods[i];
            }
        }

        if (ctorMember == null) {
            if (ctors.length == 1) {
                ctorMember = ctors[0];
            } else if (ctors.length == 2) {
                if (ctors[0].getParameterTypes().length == 0)
                    ctorMember = ctors[1];
                else if (ctors[1].getParameterTypes().length == 0)
                    ctorMember = ctors[0];
            }
            if (ctorMember == null) {
                throw new ClassDefinitionException(
                    Context.getMessage1("msg.ctor.multiple.parms",
                                        clazz.getName()));
            }
        }

        FunctionObject ctor = new FunctionObject(className, ctorMember, scope);
        if (ctor.isVarArgsMethod()) {
            throw Context.reportRuntimeError1
                ("msg.varargs.ctor", ctorMember.getName());
        }
        ctor.addAsConstructor(scope, proto);

        if (!hasPrefix && exclusionList == null)
            exclusionList = getExclusionList();
        Method finishInit = null;
        for (int i=0; i < methods.length; i++) {
            if (!hasPrefix && methods[i].getDeclaringClass() != clazz)
                continue;
            String name = methods[i].getName();
            if (name.equals("finishInit")) {
                Class[] parmTypes = methods[i].getParameterTypes();
                if (parmTypes.length == 3 &&
                    parmTypes[0] == ScriptRuntime.ScriptableClass &&
                    parmTypes[1] == FunctionObject.class &&
                    parmTypes[2] == ScriptRuntime.ScriptableClass &&
                    Modifier.isStatic(methods[i].getModifiers()))
                {
                    finishInit = methods[i];
                    continue;
                }
            }
            // ignore any compiler generated methods.
            if (name.indexOf('$') != -1)
                continue;
            if (name.equals(ctorName))
                continue;
            String prefix = null;
            if (hasPrefix) {
                if (name.startsWith(genericPrefix)) {
                    prefix = genericPrefix;
                } else if (name.startsWith(functionPrefix)) {
                    prefix = functionPrefix;
                } else if (name.startsWith(staticFunctionPrefix)) {
                    prefix = staticFunctionPrefix;
                    if (!Modifier.isStatic(methods[i].getModifiers())) {
                        throw new ClassDefinitionException(
                            "jsStaticFunction must be used with static method.");
                    }
                } else if (name.startsWith(propertyPrefix)) {
                    prefix = propertyPrefix;
                } else if (name.startsWith(getterPrefix)) {
                    prefix = getterPrefix;
                } else if (name.startsWith(setterPrefix)) {
                    prefix = setterPrefix;
                } else {
                    continue;
                }
                name = name.substring(prefix.length());
            } else if (exclusionList.get(name) != null)
                continue;
            if (methods[i] == ctorMember) {
                continue;
            }
            if (prefix != null && prefix.equals(setterPrefix))
                continue;   // deal with set when we see get
            if (prefix != null && prefix.equals(getterPrefix)) {
                if (!(proto instanceof ScriptableObject)) {
                    throw PropertyException.withMessage2
                        ("msg.extend.scriptable",                                                         proto.getClass().toString(), name);
                }
                Method[] setter = FunctionObject.findMethods(
                                    clazz,
                                    setterPrefix + name);
                if (setter != null && setter.length != 1) {
                    throw PropertyException.withMessage2
                        ("msg.no.overload", name, clazz.getName());
                }
                int attr = ScriptableObject.PERMANENT |
                           ScriptableObject.DONTENUM  |
                           (setter != null ? 0
                                           : ScriptableObject.READONLY);
                Method m = setter == null ? null : setter[0];
                ((ScriptableObject) proto).defineProperty(name, null,
                                                          methods[i], m,
                                                          attr);
                continue;
            }
            if ((name.startsWith("get") || name.startsWith("set")) &&
                name.length() > 3 &&
                !(hasPrefix && (prefix.equals(functionPrefix) ||
                                prefix.equals(staticFunctionPrefix))))
            {
                if (!(proto instanceof ScriptableObject)) {
                    throw PropertyException.withMessage2
                        ("msg.extend.scriptable",
                         proto.getClass().toString(), name);
                }
                if (name.startsWith("set"))
                    continue;   // deal with set when we see get
                StringBuffer buf = new StringBuffer();
                char c = name.charAt(3);
                buf.append(Character.toLowerCase(c));
                if (name.length() > 4)
                    buf.append(name.substring(4));
                String propertyName = buf.toString();
                buf.setCharAt(0, c);
                buf.insert(0, "set");
                String setterName = buf.toString();
                Method[] setter = FunctionObject.findMethods(
                                    clazz,
                                    hasPrefix ? genericPrefix + setterName
                                              : setterName);
                if (setter != null && setter.length != 1) {
                    throw PropertyException.withMessage2
                        ("msg.no.overload", name, clazz.getName());
                }
                if (setter == null && hasPrefix)
                    setter = FunctionObject.findMethods(
                                clazz,
                                propertyPrefix + setterName);
                int attr = ScriptableObject.PERMANENT |
                           ScriptableObject.DONTENUM  |
                           (setter != null ? 0
                                           : ScriptableObject.READONLY);
                Method m = setter == null ? null : setter[0];
                ((ScriptableObject) proto).defineProperty(propertyName, null,
                                                          methods[i], m,
                                                          attr);
                continue;
            }
            FunctionObject f = new FunctionObject(name, methods[i], proto);
            if (f.isVarArgsConstructor()) {
                throw Context.reportRuntimeError1
                    ("msg.varargs.fun", ctorMember.getName());
            }
            Scriptable dest = prefix == staticFunctionPrefix
                              ? ctor
                              : proto;
            defineProperty(dest, name, f, DONTENUM);
            if (sealed) {
                f.sealObject();
                f.addPropertyAttribute(READONLY);
            }
        }

        if (finishInit != null) {
            // call user code to complete the initialization
            Object[] finishArgs = { scope, ctor, proto };
            finishInit.invoke(null, finishArgs);
        }
        
        if (sealed) {
            ctor.sealObject();
            ctor.addPropertyAttribute(READONLY);
            if (proto instanceof ScriptableObject) {
                ((ScriptableObject) proto).sealObject();
                ((ScriptableObject) proto).addPropertyAttribute(READONLY);
            }
        }
    }

    /**
     * Define a JavaScript property.
     *
     * Creates the property with an initial value and sets its attributes.
     *
     * @param propertyName the name of the property to define.
     * @param value the initial value of the property
     * @param attributes the attributes of the JavaScript property
     * @see org.mozilla.javascript.Scriptable#put
     */
    public void defineProperty(String propertyName, Object value,
                               int attributes)
    {
        put(propertyName, this, value);
        try {
            setAttributes(propertyName, this, attributes);
        }
        catch (PropertyException e) {
            throw new RuntimeException("Cannot create property");
        }
    }

    /**
     * Utility method to add properties to arbitrary Scriptable object.
     * If destination is instance of ScriptableObject, calls 
     * defineProperty there, otherwise calls put in destination 
     * ignoring attributes
     */
    public static void defineProperty(Scriptable destination, 
                                      String propertyName, Object value,
                                      int attributes)
    {
        if (destination instanceof ScriptableObject) {
            ScriptableObject obj = (ScriptableObject)destination;
            obj.defineProperty(propertyName, value, attributes);
        }
        else {
            destination.put(propertyName, destination, value);
        }
    }
 
    /**
     * Define a JavaScript property with getter and setter side effects.
     *
     * If the setter is not found, the attribute READONLY is added to
     * the given attributes. <p>
     *
     * The getter must be a method with zero parameters, and the setter, if
     * found, must be a method with one parameter.<p>
     *
     * @param propertyName the name of the property to define. This name
     *                    also affects the name of the setter and getter
     *                    to search for. If the propertyId is "foo", then
     *                    <code>clazz</code> will be searched for "getFoo"
     *                    and "setFoo" methods.
     * @param clazz the Java class to search for the getter and setter
     * @param attributes the attributes of the JavaScript property
     * @exception PropertyException if multiple methods
     *            are found for the getter or setter, or if the getter
     *            or setter do not conform to the forms described in
     *            <code>defineProperty(String, Object, Method, Method,
     *            int)</code>
     * @see org.mozilla.javascript.Scriptable#put
     */
    public void defineProperty(String propertyName, Class clazz,
                               int attributes)
        throws PropertyException
    {
        StringBuffer buf = new StringBuffer(propertyName);
        buf.setCharAt(0, Character.toUpperCase(propertyName.charAt(0)));
        String s = buf.toString();
        Method[] getter = FunctionObject.findMethods(clazz, "get" + s);
        Method[] setter = FunctionObject.findMethods(clazz, "set" + s);
        if (setter == null)
            attributes |= ScriptableObject.READONLY;
        if (getter.length != 1 || (setter != null && setter.length != 1)) {
            throw PropertyException.withMessage2
                ("msg.no.overload", propertyName, clazz.getName());
        }
        defineProperty(propertyName, null, getter[0],
                       setter == null ? null : setter[0], attributes);
    }

    /**
     * Define a JavaScript property.
     *
     * Use this method only if you wish to define getters and setters for
     * a given property in a ScriptableObject. To create a property without
     * special getter or setter side effects, use
     * <code>defineProperty(String,int)</code>.
     *
     * If <code>setter</code> is null, the attribute READONLY is added to
     * the given attributes.<p>
     *
     * Several forms of getters or setters are allowed. In all cases the
     * type of the value parameter can be any one of the following types: 
     * Object, String, boolean, Scriptable, byte, short, int, long, float,
     * or double. The runtime will perform appropriate conversions based
     * upon the type of the parameter (see description in FunctionObject).
     * The first forms are nonstatic methods of the class referred to
     * by 'this':
     * <pre>
     * Object getFoo();
     * void setFoo(SomeType value);</pre>
     * Next are static methods that may be of any class; the object whose
     * property is being accessed is passed in as an extra argument:
     * <pre>
     * static Object getFoo(ScriptableObject obj);
     * static void setFoo(ScriptableObject obj, SomeType value);</pre>
     * Finally, it is possible to delegate to another object entirely using
     * the <code>delegateTo</code> parameter. In this case the methods are
     * nonstatic methods of the class delegated to, and the object whose
     * property is being accessed is passed in as an extra argument:
     * <pre>
     * Object getFoo(ScriptableObject obj);
     * void setFoo(ScriptableObject obj, SomeType value);</pre>
     *
     * @param propertyName the name of the property to define.
     * @param delegateTo an object to call the getter and setter methods on,
     *                   or null, depending on the form used above.
     * @param getter the method to invoke to get the value of the property
     * @param setter the method to invoke to set the value of the property
     * @param attributes the attributes of the JavaScript property
     * @exception PropertyException if the getter or setter
     *            do not conform to the forms specified above
     */
    public void defineProperty(String propertyName, Object delegateTo,
                               Method getter, Method setter, int attributes)
        throws PropertyException
    {
        int flags = Slot.HAS_GETTER;
        if (delegateTo == null && (Modifier.isStatic(getter.getModifiers())))
            delegateTo = HAS_STATIC_ACCESSORS;
        Class[] parmTypes = getter.getParameterTypes();
        if (parmTypes.length != 0) {
            if (parmTypes.length != 1 ||
                parmTypes[0] != ScriptableObject.class)
            {
                throw PropertyException.withMessage1
                    ("msg.bad.getter.parms", getter.toString());
            }
        } else if (delegateTo != null) {
            throw PropertyException.withMessage1
                ("msg.obj.getter.parms", getter.toString());
        }
        if (setter != null) {
            flags |= Slot.HAS_SETTER;
            if ((delegateTo == HAS_STATIC_ACCESSORS) !=
                (Modifier.isStatic(setter.getModifiers())))
            {
                throw PropertyException.withMessage0("msg.getter.static");
            }
            parmTypes = setter.getParameterTypes();
            if (parmTypes.length == 2) {
                if (parmTypes[0] != ScriptableObject.class) {
                    throw PropertyException.withMessage0("msg.setter2.parms");
                }
                if (delegateTo == null) {
                    throw PropertyException.withMessage1
                        ("msg.setter1.parms", setter.toString());
                }
            } else if (parmTypes.length == 1) {
                if (delegateTo != null) {
                    throw PropertyException.withMessage1
                        ("msg.setter2.expected", setter.toString());
                }
            } else {
                throw PropertyException.withMessage0("msg.setter.parms");
            }
        }
        GetterSlot slot = (GetterSlot)getSlotToSet(propertyName,
                                                   propertyName.hashCode(),
                                                   true);
        slot.delegateTo = delegateTo;
        slot.getter = getter;
        slot.setter = setter;
        slot.setterReturnsValue = setter != null && setter.getReturnType() != Void.TYPE;
        slot.value = null;
        slot.attributes = (short) attributes;
        slot.flags = (byte)flags;
    }

    /**
     * Search for names in a class, adding the resulting methods
     * as properties.
     *
     * <p> Uses reflection to find the methods of the given names. Then
     * FunctionObjects are constructed from the methods found, and
     * are added to this object as properties with the given names.
     *
     * @param names the names of the Methods to add as function properties
     * @param clazz the class to search for the Methods
     * @param attributes the attributes of the new properties
     * @exception PropertyException if any of the names
     *            has no corresponding method or more than one corresponding
     *            method in the class
     * @see org.mozilla.javascript.FunctionObject
     */
    public void defineFunctionProperties(String[] names, Class clazz,
                                         int attributes)
        throws PropertyException
    {
        for (int i=0; i < names.length; i++) {
            String name = names[i];
            Method[] m = FunctionObject.findMethods(clazz, name);
            if (m == null) {
                throw PropertyException.withMessage2
                    ("msg.method.not.found", name, clazz.getName());
            }
            if (m.length > 1) {
                throw PropertyException.withMessage2
                    ("msg.no.overload", name, clazz.getName());
            }
            FunctionObject f = new FunctionObject(name, m[0], this);
            defineProperty(name, f, attributes);
        }
    }

    /**
     * Get the Object.prototype property.
     * See ECMA 15.2.4.
     */
    public static Scriptable getObjectPrototype(Scriptable scope) {
        return getClassPrototype(scope, "Object");
    }

    /**
     * Get the Function.prototype property.
     * See ECMA 15.3.4.
     */
    public static Scriptable getFunctionPrototype(Scriptable scope) {
        return getClassPrototype(scope, "Function");
    }

    /**
     * Get the prototype for the named class.
     *
     * For example, <code>getClassPrototype(s, "Date")</code> will first
     * walk up the parent chain to find the outermost scope, then will
     * search that scope for the Date constructor, and then will
     * return Date.prototype. If any of the lookups fail, or
     * the prototype is not a JavaScript object, then null will
     * be returned.
     *
     * @param scope an object in the scope chain
     * @param className the name of the constructor
     * @return the prototype for the named class, or null if it
     *         cannot be found.
     */
    public static Scriptable getClassPrototype(Scriptable scope,
                                               String className)
    {
        scope = getTopLevelScope(scope);
        Object ctor = ScriptRuntime.getTopLevelProp(scope, className);
        if (ctor == NOT_FOUND || !(ctor instanceof Scriptable))
            return null;
        Scriptable ctorObj = (Scriptable) ctor;
        if (!ctorObj.has("prototype", ctorObj))
            return null;
        Object proto = ctorObj.get("prototype", ctorObj);
        if (!(proto instanceof Scriptable))
            return null;
        return (Scriptable) proto;
    }

    /**
     * Get the global scope.
     *
     * <p>Walks the parent scope chain to find an object with a null
     * parent scope (the global object).
     *
     * @param obj a JavaScript object
     * @return the corresponding global scope
     */
    public static Scriptable getTopLevelScope(Scriptable obj) {
        Scriptable next = obj;
        do {
            obj = next;
            next = obj.getParentScope();
        } while (next != null);
        return obj;
    }
    
    /**
     * Seal this object.
     * 
     * A sealed object may not have properties added or removed. Once
     * an object is sealed it may not be unsealed.
     * 
     * @since 1.4R3
     */
    public void sealObject() {
        count = -1;
    }
    
    /**
     * Return true if this object is sealed.
     * 
     * It is an error to attempt to add or remove properties to 
     * a sealed object.
     * 
     * @return true if sealed, false otherwise.
     * @since 1.4R3
     */
    public boolean isSealed() {
        return count == -1;
    }

    /**
     * Gets a named property from an object or any object in its prototype chain.
     * <p>
     * Searches the prototype chain for a property named <code>name</code>.
     * <p>
     * @param obj a JavaScript object 
     * @param name a property name 
     * @return the value of a property with name <code>name</code> found in 
     *         <code>obj</code> or any object in its prototype chain, or 
     *         <code>Scriptable.NOT_FOUND</code> if not found
     * @since 1.5R2
     */
    public static Object getProperty(Scriptable obj, String name) {
        Scriptable start = obj;
        Object result;
        do {
            result = obj.get(name, start);
            if (result != Scriptable.NOT_FOUND)
                break;
            obj = obj.getPrototype();
        } while (obj != null);
        return result;
    }
    
    /**
     * Gets an indexed property from an object or any object in its prototype chain.
     * <p>
     * Searches the prototype chain for a property with integral index 
     * <code>index</code>. Note that if you wish to look for properties with numerical
     * but non-integral indicies, you should use getProperty(Scriptable,String) with
     * the string value of the index.
     * <p>
     * @param obj a JavaScript object 
     * @param index an integral index 
     * @return the value of a property with index <code>index</code> found in 
     *         <code>obj</code> or any object in its prototype chain, or 
     *         <code>Scriptable.NOT_FOUND</code> if not found
     * @since 1.5R2
     */
    public static Object getProperty(Scriptable obj, int index) {
        Scriptable start = obj;
        Object result;
        do {
            result = obj.get(index, start);
            if (result != Scriptable.NOT_FOUND)
                break;
            obj = obj.getPrototype();
        } while (obj != null);
        return result;
    }
    
    /**
     * Returns whether a named property is defined in an object or any object 
     * in its prototype chain.
     * <p>
     * Searches the prototype chain for a property named <code>name</code>.
     * <p>
     * @param obj a JavaScript object 
     * @param name a property name 
     * @return the true if property was found
     * @since 1.5R2
     */
    public static boolean hasProperty(Scriptable obj, String name) {
        Scriptable start = obj;
        do {
            if (obj.has(name, start))
                return true;
            obj = obj.getPrototype();
        } while (obj != null);
        return false;
    }
    
    /**
     * Returns whether an indexed property is defined in an object or any object 
     * in its prototype chain.
     * <p>
     * Searches the prototype chain for a property with index <code>index</code>.
     * <p>
     * @param obj a JavaScript object 
     * @param index a property index 
     * @return the true if property was found
     * @since 1.5R2
     */
    public static boolean hasProperty(Scriptable obj, int index) {
        Scriptable start = obj;
        do {
            if (obj.has(index, start))
                return true;
            obj = obj.getPrototype();
        } while (obj != null);
        return false;
    }

    /**
     * Puts a named property in an object or in an object in its prototype chain.
     * <p>
     * Seaches for the named property in the prototype chain. If it is found,
     * the value of the property is changed. If it is not found, a new
     * property is added in <code>obj</code>.
     * @param obj a JavaScript object 
     * @param name a property name
     * @param value any JavaScript value accepted by Scriptable.put 
     * @since 1.5R2
     */
    public static void putProperty(Scriptable obj, String name, Object value) {
        Scriptable base = getBase(obj, name);
        if (base == null)
            base = obj;
        base.put(name, obj, value);
    }

    /**
     * Puts an indexed property in an object or in an object in its prototype chain.
     * <p>
     * Seaches for the indexed property in the prototype chain. If it is found,
     * the value of the property is changed. If it is not found, a new
     * property is added in <code>obj</code>.
     * @param obj a JavaScript object 
     * @param index a property index
     * @param value any JavaScript value accepted by Scriptable.put 
     * @since 1.5R2
     */
    public static void putProperty(Scriptable obj, int index, Object value) {
        Scriptable base = getBase(obj, index);
        if (base == null)
            base = obj;
        base.put(index, obj, value);
    }

    /**
     * Removes the property from an object or its prototype chain.
     * <p>
     * Searches for a property with <code>name</code> in obj or
     * its prototype chain. If it is found, the object's delete
     * method is called. 
     * @param obj a JavaScript object
     * @param name a property name
     * @return true if the property doesn't exist or was successfully removed
     * @since 1.5R2
     */
    public static boolean deleteProperty(Scriptable obj, String name) {
        Scriptable base = getBase(obj, name);
        if (base == null)
            return true;
        base.delete(name);
        return base.get(name, obj) == NOT_FOUND;
    }
                
    /**
     * Removes the property from an object or its prototype chain.
     * <p>
     * Searches for a property with <code>index</code> in obj or
     * its prototype chain. If it is found, the object's delete
     * method is called. 
     * @param obj a JavaScript object
     * @param index a property index
     * @return true if the property doesn't exist or was successfully removed
     * @since 1.5R2
     */
    public static boolean deleteProperty(Scriptable obj, int index) {
        Scriptable base = getBase(obj, index);
        if (base == null)
            return true;
        base.delete(index);
        return base.get(index, obj) == NOT_FOUND;
    }
    
    /**
     * Returns an array of all ids from an object and its prototypes.
     * <p>
     * @param obj a JavaScript object
     * @return an array of all ids from all object in the prototype chain.
     *         If a given id occurs multiple times in the prototype chain,
     *         it will occur only once in this list.
     * @since 1.5R2
     */
    public static Object[] getPropertyIds(Scriptable obj) {
        Hashtable h = new Hashtable();  // JDK1.2: use HashSet
        while (obj != null) {
            Object[] ids = obj.getIds();
            for (int i=0; i < ids.length; i++) {
                h.put(ids[i], ids[i]);
            }
            obj = (Scriptable)obj.getPrototype();
        }
        Object[] result = new Object[h.size()];
        java.util.Enumeration e = h.elements();
        int n = 0;
        while (e.hasMoreElements()) {
            result[n++] = e.nextElement();
        }
        return result;
    }
    
    /**
     * Call a method of an object.
     * <p>
     * @param obj the JavaScript object
     * @param methodName the name of the function property
     * @param args the arguments for the call
     * @exception JavaScriptException thrown if there were errors in the call
     */
    public static Object callMethod(Scriptable obj, String methodName, 
                                    Object[] args)
        throws JavaScriptException
    {
        Context cx = Context.enter();
        try {
            Object fun = getProperty(obj, methodName);
            if (fun == NOT_FOUND)
                fun = Undefined.instance;
            return ScriptRuntime.call(cx, fun, obj, args, getTopLevelScope(obj));
        } finally {
          Context.exit();
        }
    }
                
    private static Scriptable getBase(Scriptable obj, String s) {
        Scriptable m = obj;
        while (m != null) {
            if (m.has(s, obj))
                return m;
            m = m.getPrototype();
        }
        return null;
    }

    private static Scriptable getBase(Scriptable obj, int index) {
        Scriptable m = obj;
        while (m != null) {
            if (m.has(index, obj))
                return m;
            m = m.getPrototype();
        }
        return null;
    }
    
    /**
     * Adds a property attribute to all properties.
     */
    synchronized void addPropertyAttribute(int attribute) {
        if (slots == null)
            return;
        for (int i=0; i < slots.length; i++) {
            Slot slot = slots[i];
            if (slot == null || slot == REMOVED)
                continue;
            if ((slot.flags & slot.HAS_SETTER) != 0 && attribute == READONLY)
                continue;
            slot.attributes |= attribute;
        }
    }
    
    private Slot getSlot(String id, int index, boolean shouldDelete) {
        Slot[] slots = this.slots;
        if (slots == null)
            return null;
        int start = (index & 0x7fffffff) % slots.length;
        int i = start;
        do {
            Slot slot = slots[i];
            if (slot == null)
                return null;
            if (slot != REMOVED && slot.intKey == index && 
                (slot.stringKey == id || (id != null && 
                                          id.equals(slot.stringKey))))
            {
                if (shouldDelete) {
                    if ((slot.attributes & PERMANENT) == 0) {
                        // Mark the slot as removed to handle a case when
                        // another thread manages to put just removed slot
                        // into lastAccess cache.
                        slot.wasDeleted = (byte)1;
                        slots[i] = REMOVED;
                        count--;
                        if (slot == lastAccess)
                            lastAccess = REMOVED;
                    }
                }
                return slot;
            }
            if (++i == slots.length)
                i = 0;
        } while (i != start);
        return null;
    }

    private Slot getSlotToSet(String id, int index, boolean getterSlot) {
        if (slots == null)
            slots = new Slot[5];
        int start = (index & 0x7fffffff) % slots.length;
        boolean sawRemoved = false;
        int i = start;
        do {
            Slot slot = slots[i];
            if (slot == null) {
                return addSlot(id, index, getterSlot);
            }
            if (slot == REMOVED) {
                sawRemoved = true;
            } else if (slot.intKey == index && 
                       (slot.stringKey == id || 
                        (id != null && id.equals(slot.stringKey))))
            {
                return slot;
            }
            if (++i == slots.length)
                i = 0;
        } while (i != start);
        if (sawRemoved) {
            // Table could be full, but with some REMOVED elements. 
            // Call to addSlot will use a slot currently taken by 
            // a REMOVED.
            return addSlot(id, index, getterSlot);
        }
        throw new RuntimeException("Hashtable internal error");
    }

    /**
     * Add a new slot to the hash table.
     *
     * This method must be synchronized since it is altering the hash
     * table itself. Note that we search again for the slot to set
     * since another thread could have added the given property or
     * caused the table to grow while this thread was searching.
     */
    private synchronized Slot addSlot(String id, int index, boolean getterSlot)
    {
        if (count == -1)
            throw Context.reportRuntimeError0("msg.add.sealed");
        int start = (index & 0x7fffffff) % slots.length;
        int i = start;
        do {
            Slot slot = slots[i];
            if (slot == null || slot == REMOVED) {
                if ((4 * (count+1)) > (3 * slots.length)) {
                    grow();
                    return getSlotToSet(id, index, getterSlot);
                }
                slot = getterSlot ? new GetterSlot() : new Slot();
                slot.stringKey = id;
                slot.intKey = index;
                slots[i] = slot;
                count++;
                return slot;
            }
            if (slot.intKey == index && 
                (slot.stringKey == id || (id != null && 
                                          id.equals(slot.stringKey)))) 
            {
                return slot;
            }
            if (++i == slots.length)
                i = 0;
        } while (i != start);
        throw new RuntimeException("Hashtable internal error");
    }

    /**
     * Remove a slot from the hash table.
     *
     * This method must be synchronized since it is altering the hash
     * table itself. We might be able to optimize this more, but
     * deletes are not common.
     */
    private synchronized void removeSlot(String name, int index) {
        if (count == -1)
            throw Context.reportRuntimeError0("msg.remove.sealed");
        getSlot(name, index, true);
    }

    /**
     * Grow the hash table to accommodate new entries.
     *
     * Note that by assigning the new array back at the end we
     * can continue reading the array from other threads.
     */
    private synchronized void grow() {
        Slot[] newSlots = new Slot[slots.length*2 + 1];
        for (int j=slots.length-1; j >= 0 ; j--) {
            Slot slot = slots[j];
            if (slot == null || slot == REMOVED)
                continue;
            int k = (slot.intKey & 0x7fffffff) % newSlots.length;
            while (newSlots[k] != null)
                if (++k == newSlots.length)
                    k = 0;
            // The end of the "synchronized" statement will cause the memory
            // writes to be propagated on a multiprocessor machine. We want
            // to make sure that the new table is prepared to be read.
            // XXX causes the 'this' pointer to be null in calling stack frames
            // on the MS JVM
            //synchronized (slot) { }
            newSlots[k] = slot;
        }
        slots = newSlots;
    }

    private static Hashtable getExclusionList() {
        if (exclusionList != null)
            return exclusionList;
        Hashtable result = new Hashtable(17);
        Method[] methods = ScriptRuntime.FunctionClass.getMethods();
        for (int i=0; i < methods.length; i++) {
            result.put(methods[i].getName(), Boolean.TRUE);
        }
        exclusionList = result;
        return result;
    }
        
    Object[] getIds(boolean getAll) {
        Slot[] s = slots;
        Object[] a = ScriptRuntime.emptyArgs;
        if (s == null)
            return a;
        int c = 0;
        for (int i=0; i < s.length; i++) {
            Slot slot = s[i];
            if (slot == null || slot == REMOVED)
                continue;
            if (getAll || (slot.attributes & DONTENUM) == 0) {
                if (c == 0)
                    a = new Object[s.length - i];
                a[c++] = slot.stringKey != null
                             ? (Object) slot.stringKey
                             : new Integer(slot.intKey);
            }
        }
        if (c == a.length)
            return a;
        Object[] result = new Object[c];
        System.arraycopy(a, 0, result, 0, c);
        return result;
    }

    
    /**
     * The prototype of this object.
     */
    protected Scriptable prototype;
    
    /**
     * The parent scope of this object.
     */
    protected Scriptable parent;

    private static final Object HAS_STATIC_ACCESSORS = Void.TYPE;
    private static final Slot REMOVED = new Slot();
    private static Hashtable exclusionList = null;
    
    private Slot[] slots;
    private int count;

    // cache; may be removed for smaller memory footprint
    private Slot lastAccess = REMOVED;

    private static class Slot {
        static final int HAS_GETTER  = 0x01;
        static final int HAS_SETTER  = 0x02;
        
        int intKey;
        String stringKey;
        Object value;
        short attributes;
        byte flags;
        byte wasDeleted;
    }

    private static class GetterSlot extends Slot {
        Object delegateTo;  // OPT: merge with "value"
        Method getter;
        Method setter;
        boolean setterReturnsValue;
    }

    private static final Class ContextClass = Context.class;
}
