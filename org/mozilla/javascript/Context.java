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
 * Copyright (C) 1997-2000 Netscape Communications Corporation. All
 * Rights Reserved.
 *
 * Contributor(s):
 *
 * Patrick Beard
 * Norris Boyd
 * Brendan Eich
 * Roger Lawrence
 * Mike McCabe
 * Ian D. Stewart
 * Andi Vajda
 * Andrew Wason
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

import java.beans.*;
import java.io.*;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;
import java.util.Locale;
import java.util.ResourceBundle;
import java.text.MessageFormat;
import java.lang.reflect.*;

/**
 * This class represents the runtime context of an executing script.
 *
 * Before executing a script, an instance of Context must be created
 * and associated with the thread that will be executing the script.
 * The Context will be used to store information about the executing
 * of the script such as the call stack. Contexts are associated with
 * the current thread  using the <a href="#enter()">enter()</a> method.<p>
 *
 * The behavior of the execution engine may be altered through methods
 * such as <a href="#setLanguageVersion>setLanguageVersion</a> and
 * <a href="#setErrorReporter>setErrorReporter</a>.<p>
 *
 * Different forms of script execution are supported. Scripts may be
 * evaluated from the source directly, or first compiled and then later
 * executed. Interactive execution is also supported.<p>
 *
 * Some aspects of script execution, such as type conversions and
 * object creation, may be accessed directly through methods of
 * Context.
 *
 * @see Scriptable
 * @author Norris Boyd
 * @author Brendan Eich
 */

public final class Context {
    public static String languageVersionProperty = "language version";
    public static String errorReporterProperty   = "error reporter";
    
    /**
     * Create a new Context.
     *
     * Note that the Context must be associated with a thread before
     * it can be used to execute a script.
     *
     * @see org.mozilla.javascript.Context#enter
     */
    public Context() {
        setLanguageVersion(VERSION_DEFAULT);
        this.generatingDebug = true;
        optimizationLevel = codegenClass != null ? 0 : -1;
    }
    
    /**
     * Create a new context with the associated security support.
     * 
     * @param securitySupport an encapsulation of the functionality 
     *        needed to support security for scripts.
     * @see org.mozilla.javascript.SecuritySupport
     */
    public Context(SecuritySupport securitySupport) {
        this();
        this.securitySupport = securitySupport;
    }
        
    /**
     * Get a context associated with the current thread, creating
     * one if need be.
     *
     * The Context stores the execution state of the JavaScript
     * engine, so it is required that the context be entered
     * before execution may begin. Once a thread has entered
     * a Context, then getCurrentContext() may be called to find
     * the context that is associated with the current thread.
     * <p>
     * Calling <code>enter()</code> will
     * return either the Context currently associated with the
     * thread, or will create a new context and associate it 
     * with the current thread. Each call to <code>enter()</code>
     * must have a matching call to <code>exit()</code>. For example,
     * <pre>
     *      Context cx = Context.enter();
     *      ...
     *      cx.evaluateString(...);
     *      Context.exit();
     * </pre>
     * @return a Context associated with the current thread
     * @see org.mozilla.javascript.Context#getCurrentContext
     * @see org.mozilla.javascript.Context#exit
     */
    public static Context enter() {
        return enter(null);
    }
    
    /**
     * Get a Context associated with the current thread, using
     * the given Context if need be.
     * <p>
     * The same as <code>enter()</code> except that <code>cx</code>
     * is associated with the current thread and returned if 
     * the current thread has no associated context and <code>cx</code>
     * is not associated with any other thread.
     * @param cx a Context to associate with the thread if possible
     * @return a Context associated with the current thread
     */
    public static Context enter(Context cx) {
        // There's some duplication of code in this method to avoid
        // unnecessary synchronizations.
        Thread t = Thread.currentThread();
        Context current = (Context) threadContexts.get(t);
        if (current != null) {
            synchronized (current) {
                current.enterCount++;
            }
            return current;
        }
        if (cx != null) {
            synchronized (cx) {
                if (cx.currentThread == null) {
                    cx.currentThread = t;
                    threadContexts.put(t, cx);
                    cx.enterCount++;
                    return cx;
                }
            }
        }
        current = new Context();
        current.currentThread = t;
        threadContexts.put(t, current);
        current.enterCount = 1;
        return current;
     }
        
    /**
     * Exit a block of code requiring a Context.
     *
     * Calling <code>exit()</code> will remove the association between
     * the current thread and a Context if the prior call to 
     * <code>enter()</code> on this thread newly associated a Context 
     * with this thread.
     * Once the current thread no longer has an associated Context,
     * it cannot be used to execute JavaScript until it is again associated
     * with a Context.
     *
     * @see org.mozilla.javascript.Context#enter
     */
    public static void exit() {
        Context cx = getCurrentContext();
        if (cx != null) {
            synchronized (cx) {
                if (--cx.enterCount == 0) {
                    threadContexts.remove(cx.currentThread);
                    cx.currentThread = null;
                }
            }
        }
    }

    /**
     * Get the current Context.
     *
     * The current Context is per-thread; this method looks up
     * the Context associated with the current thread. <p>
     *
     * @return the Context associated with the current thread, or
     *         null if no context is associated with the current 
     *         thread.
     * @see org.mozilla.javascript.Context#enter
     * @see org.mozilla.javascript.Context#exit
     */
    public static Context getCurrentContext() {
        Thread t = Thread.currentThread();
        return (Context) threadContexts.get(t);
    }
    
    /**
     * Language versions
     *
     * All integral values are reserved for future version numbers.
     */

    /**
     * The unknown version.
     */
    public static final int VERSION_UNKNOWN =   -1;

    /**
     * The default version.
     */
    public static final int VERSION_DEFAULT =    0;

    /**
     * JavaScript 1.0
     */
    public static final int VERSION_1_0 =      100;

    /**
     * JavaScript 1.1
     */
    public static final int VERSION_1_1 =      110;

    /**
     * JavaScript 1.2
     */
    public static final int VERSION_1_2 =      120;

    /**
     * JavaScript 1.3
     */
    public static final int VERSION_1_3 =      130;

    /**
     * JavaScript 1.4
     */
    public static final int VERSION_1_4 =      140;

    /**
     * JavaScript 1.5
     */
    public static final int VERSION_1_5 =      150;

    /**
     * Get the current language version.
     * <p>
     * The language version number affects JavaScript semantics as detailed
     * in the overview documentation.
     *
     * @return an integer that is one of VERSION_1_0, VERSION_1_1, etc.
     */
    public int getLanguageVersion() {
       return version;
    }

    /**
     * Set the language version.
     *
     * <p>
     * Setting the language version will affect functions and scripts compiled
     * subsequently. See the overview documentation for version-specific
     * behavior.
     *
     * @param version the version as specified by VERSION_1_0, VERSION_1_1, etc.
     */
    public void setLanguageVersion(int version) {
        if (listeners != null && version != this.version) {
            firePropertyChange(languageVersionProperty, 
                               new Integer(this.version), 
                               new Integer(version));
        }
        this.version = version;
    }

    /**
     * Get the implementation version.
     *
     * <p>
     * The implementation version is of the form 
     * <pre>
     *    "<i>name langVer</i> <code>release</code> <i>relNum date</i>"
     * </pre>
     * where <i>name</i> is the name of the product, <i>langVer</i> is 
     * the language version, <i>relNum</i> is the release number, and 
     * <i>date</i> is the release date for that specific 
     * release in the form "yyyy mm dd". 
     *
     * @return a string that encodes the product, language version, release 
     *         number, and date.
     */
     public String getImplementationVersion() {
        return "JavaScript-Java 1.5 release 1 2000 03 15";
     }

    /**
     * Get the current error reporter.
     *
     * @see org.mozilla.javascript.ErrorReporter
     */
    public ErrorReporter getErrorReporter() {
        if (debug_errorReporterHook != null) {
            return debug_errorReporterHook;
        }
        if (errorReporter == null) {
            errorReporter = new DefaultErrorReporter();
        }
        return errorReporter;
    }

    /**
     * Change the current error reporter.
     *
     * @return the previous error reporter
     * @see org.mozilla.javascript.ErrorReporter
     */
    public ErrorReporter setErrorReporter(ErrorReporter reporter) {
        if (null != debug_errorReporterHook)
            return debug_errorReporterHook.setErrorReporter(reporter);
        ErrorReporter result = errorReporter;
        if (listeners != null && errorReporter != reporter) {
            firePropertyChange(errorReporterProperty, errorReporter, 
                               reporter);
        }
        errorReporter = reporter;
        return result;
    }

    /**
     * Get the current locale.  Returns the default locale if none has
     * been set.
     *
     * @see java.util.Locale
     */

    public Locale getLocale() {
        if (locale == null)
            locale = Locale.getDefault();
        return locale;
    }

    /**
     * Set the current locale.
     *
     * @see java.util.Locale
     */
    public Locale setLocale(Locale loc) {
        Locale result = locale;
        locale = loc;
        return result;
    }
    
    /**
     * Register an object to receive notifications when a bound property
     * has changed
     * @see java.beans.PropertyChangeEvent
     * @see #removePropertyChangeListener(java.beans.PropertyChangeListener)
     * @param  listener  the listener
     */
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        if (listeners == null) {
            listeners = new ListenerCollection();
        }    
        listeners.addListener(listener);
    }
    
    /**
     * Remove an object from the list of objects registered to receive 
     * notification of changes to a bounded property
     * @see java.beans.PropertyChangeEvent
     * @see #addPropertyChangeListener(java.beans.PropertyChangeListener)
     * @param listener  the listener
     */
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        listeners.removeListener(listener);
    }
    
    /**
     * Notify any registered listeners that a bounded property has changed
     * @see #addPropertyChangeListener(java.beans.PropertyChangeListener)
     * @see #removePropertyChangeListener(java.beans.PropertyChangeListener)
     * @see java.beans.PropertyChangeListener
     * @see java.beans.PropertyChangeEvent
     * @param  property  the bound property
     * @param  oldValue  the old value
     * @param  newVale   the new value
     */
    protected void firePropertyChange(String property, Object oldValue,
                                      Object newValue) {
            Class listenerClass = java.beans.PropertyChangeListener.class;
            Object[] listenerList = listeners.getListeners(listenerClass);
            for(int i = 0; i < listenerList.length; i++) {
                PropertyChangeListener l = 
                    (PropertyChangeListener)listenerList[i];    
                l.propertyChange(new PropertyChangeEvent(
                    this, property, oldValue, newValue));
            }
    }
                                    
    /**
     * Report a warning using the error reporter for the current thread.
     *
     * @param message the warning message to report
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number
     * @param lineSource the text of the line (may be null)
     * @param lineOffset the offset into lineSource where problem was detected
     * @see org.mozilla.javascript.ErrorReporter
     */
    public static void reportWarning(String message, String sourceName,
                                     int lineno, String lineSource,
                                     int lineOffset)
    {
        Context cx = Context.getContext();
        cx.getErrorReporter().warning(message, sourceName, lineno,
                                      lineSource, lineOffset);
    }

    /**
     * Report a warning using the error reporter for the current thread.
     *
     * @param message the warning message to report
     * @see org.mozilla.javascript.ErrorReporter
     */
    public static void reportWarning(String message) {
        int[] linep = { 0 };
        String filename = getSourcePositionFromStack(linep);
        Context.reportWarning(message, filename, linep[0], null, 0);
    }

    /**
     * Report an error using the error reporter for the current thread.
     *
     * @param message the error message to report
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number
     * @param lineSource the text of the line (may be null)
     * @param lineOffset the offset into lineSource where problem was detected
     * @see org.mozilla.javascript.ErrorReporter
     */
    public static void reportError(String message, String sourceName,
                                   int lineno, String lineSource,
                                   int lineOffset)
    {
        Context cx = getCurrentContext();
        if (cx != null) {
            cx.errorCount++;
            cx.getErrorReporter().error(message, sourceName, lineno,
                                        lineSource, lineOffset);
        } else {
            throw new EvaluatorException(message);
        }
    }

    /**
     * Report an error using the error reporter for the current thread.
     *
     * @param message the error message to report
     * @see org.mozilla.javascript.ErrorReporter
     */
    public static void reportError(String message) {
        int[] linep = { 0 };
        String filename = getSourcePositionFromStack(linep);
        Context.reportError(message, filename, linep[0], null, 0);
    }

    /**
     * Report a runtime error using the error reporter for the current thread.
     *
     * @param message the error message to report
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number
     * @param lineSource the text of the line (may be null)
     * @param lineOffset the offset into lineSource where problem was detected
     * @return a runtime exception that will be thrown to terminate the
     *         execution of the script
     * @see org.mozilla.javascript.ErrorReporter
     */
    public static EvaluatorException reportRuntimeError(String message,
                                                      String sourceName,
                                                      int lineno,
                                                      String lineSource,
                                                      int lineOffset)
    {
        Context cx = getCurrentContext();
        if (cx != null) {
            cx.errorCount++;
            return cx.getErrorReporter().
                            runtimeError(message, sourceName, lineno,
                                         lineSource, lineOffset);
        } else {
            throw new EvaluatorException(message);
        }
    }

    /**
     * Report a runtime error using the error reporter for the current thread.
     *
     * @param message the error message to report
     * @see org.mozilla.javascript.ErrorReporter
     */
    public static EvaluatorException reportRuntimeError(String message) {
        int[] linep = { 0 };
        String filename = getSourcePositionFromStack(linep);
        return Context.reportRuntimeError(message, filename, linep[0], null, 0);
    }

    /**
     * Initialize the standard objects.
     *
     * Creates instances of the standard objects and their constructors
     * (Object, String, Number, Date, etc.), setting up 'scope' to act
     * as a global object as in ECMA 15.1.<p>
     *
     * This method must be called to initialize a scope before scripts
     * can be evaluated in that scope.
     *
     * @param scope the scope to initialize, or null, in which case a new
     *        object will be created to serve as the scope
     * @return the initialized scope
     */
    public Scriptable initStandardObjects(ScriptableObject scope) {
        return initStandardObjects(scope, false);
    }
    
    /**
     * Initialize the standard objects.
     *
     * Creates instances of the standard objects and their constructors
     * (Object, String, Number, Date, etc.), setting up 'scope' to act
     * as a global object as in ECMA 15.1.<p>
     *
     * This method must be called to initialize a scope before scripts
     * can be evaluated in that scope.<p>
     * 
     * This form of the method also allows for creating "sealed" standard
     * objects. An object that is sealed cannot have properties added or
     * removed. This is useful to create a "superglobal" that can be shared 
     * among several top-level objects. Note that sealing is not allowed in
     * the current ECMA/ISO language specification, but is likely for
     * the next version.
     *
     * @param scope the scope to initialize, or null, in which case a new
     *        object will be created to serve as the scope
     * @param sealed whether or not to create sealed standard objects that
     *        cannot be modified. 
     * @return the initialized scope
     * @since 1.4R3
     */
    public ScriptableObject initStandardObjects(ScriptableObject scope, 
                                                boolean sealed) 
    {
        final String omj = "org.mozilla.javascript.";
        try {
            if (scope == null)
                scope = new NativeObject();
            ScriptableObject.defineClass(scope, NativeFunction.class, sealed);
            ScriptableObject.defineClass(scope, NativeObject.class, sealed);

            Scriptable objectProto = ScriptableObject.
                                      getObjectPrototype(scope);

            // Function.prototype.__proto__ should be Object.prototype
            Scriptable functionProto = ScriptableObject.
                                        getFunctionPrototype(scope);
            functionProto.setPrototype(objectProto);

            // Set the prototype of the object passed in if need be
            if (scope.getPrototype() == null)
                scope.setPrototype(objectProto);
            
            // must precede NativeGlobal since it's needed therein
            ScriptableObject.defineClass(scope, NativeError.class, sealed);
            ScriptableObject.defineClass(scope, NativeGlobal.class, sealed);                                     

            String[] classes = { "NativeArray",         "Array",
                                 "NativeString",        "String",
                                 "NativeBoolean",       "Boolean",
                                 "NativeNumber",        "Number",
                                 "NativeDate",          "Date",
                                 "NativeMath",          "Math",
                                 "NativeCall",          "Call",
                                 "NativeWith",          "With",
                                 "regexp.NativeRegExp", "RegExp",
                                 "NativeScript",        "Script",
                               };
            for (int i=0; i < classes.length; i+=2) {
                try {
                    if (sealed) {
                        Class c = Class.forName(omj + classes[i]);
                        ScriptableObject.defineClass(scope, c, sealed);
                    } else {
                        String s = omj + classes[i];
                        new LazilyLoadedCtor(scope, classes[i+1], s, 
                                             ScriptableObject.DONTENUM);
                    }
                } catch (ClassNotFoundException e) {
                    continue;
                }
            }
            
            // Define the JavaAdapter class, allowing it to be overridden.
            String adapterName = "org.mozilla.javascript.JavaAdapter";
            try {
                adapterName = System.getProperty(adapterName, adapterName);
            } catch (SecurityException e) {
                // We may not be allowed to get system properties. Just
                // use the default adapter in that case.
            }
            try {
                Class adapterClass = Class.forName(adapterName);
                ScriptableObject.defineClass(scope, adapterClass, sealed);
                
                // This creates the Packages and java package roots.
                Class c = Class.forName(omj + "NativeJavaPackage");
                ScriptableObject.defineClass(scope, c, sealed);
            } catch (ClassNotFoundException e) {
                // If the class is not found, proceed without it.
            } catch (SecurityException e) {
                // Ignore AccessControlExceptions that may occur if a
                //    SecurityManager is installed:
                //  java.lang.RuntimePermission createClassLoader
                //  java.util.PropertyPermission 
                //        org.mozilla.javascript.JavaAdapter read
            }
        }
        // All of these exceptions should not occur since we are initializing
        // from known classes
        catch (IllegalAccessException e) {
            throw WrappedException.wrapException(e);
        }
        catch (InstantiationException e) {
            throw WrappedException.wrapException(e);
        }
        catch (InvocationTargetException e) {
            throw WrappedException.wrapException(e);
        }
        catch (ClassDefinitionException e) {
            throw WrappedException.wrapException(e);
        }
        catch (PropertyException e) {
            throw WrappedException.wrapException(e);
        }

        return scope;
    }

    /**
     * Get the singleton object that represents the JavaScript Undefined value.
     */
    public static Object getUndefinedValue() {
        return Undefined.instance;
    }
    
    /**
     * Evaluate a JavaScript source string.
     *
     * The provided source name and line number are used for error messages
     * and for producing debug information.
     *
     * @param scope the scope to execute in
     * @param source the JavaScript source
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number
     * @param securityDomain an arbitrary object that specifies security 
     *        information about the origin or owner of the script. For 
     *        implementations that don't care about security, this value 
     *        may be null.
     * @return the result of evaluating the string
     * @exception JavaScriptException if an uncaught JavaScript exception
     *            occurred while evaluating the source string
     * @see org.mozilla.javascript.SecuritySupport
     */
    public Object evaluateString(Scriptable scope, String source,
                                 String sourceName, int lineno,
                                 Object securityDomain)
        throws JavaScriptException
    {
        try {
            Reader in = new StringReader(source);
            return evaluateReader(scope, in, sourceName, lineno, 
                                  securityDomain);
        }
        catch (IOException ioe) {
            // Should never occur because we just made the reader from a String
            throw new RuntimeException();
        }
    }

    /**
     * Evaluate a reader as JavaScript source.
     *
     * All characters of the reader are consumed.
     *
     * @param scope the scope to execute in
     * @param in the Reader to get JavaScript source from
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number
     * @param securityDomain an arbitrary object that specifies security 
     *        information about the origin or owner of the script. For 
     *        implementations that don't care about security, this value 
     *        may be null.
     * @return the result of evaluating the source
     *
     * @exception IOException if an IOException was generated by the Reader
     * @exception JavaScriptException if an uncaught JavaScript exception
     *            occurred while evaluating the Reader
     */
    public Object evaluateReader(Scriptable scope, Reader in,
                                 String sourceName, int lineno,
                                 Object securityDomain)
        throws IOException, JavaScriptException
    {
        Script script = compileReader(scope, in, sourceName, lineno, 
                                      securityDomain);
        if (script != null)
            return script.exec(this, scope);
        else
            return null;
    }

    /**
     * Check whether a string is ready to be compiled.
     * <p>
     * stringIsCompilableUnit is intended to support interactive compilation of
     * javascript.  If compiling the string would result in an error
     * that might be fixed by appending more source, this method
     * returns false.  In every other case, it returns true.
     * <p>
     * Interactive shells may accumulate source lines, using this
     * method after each new line is appended to check whether the
     * statement being entered is complete.
     *
     * @param source the source buffer to check
     * @return whether the source is ready for compilation
     * @since 1.4 Release 2
     */
    synchronized public boolean stringIsCompilableUnit(String source)
    {
        Reader in = new StringReader(source);
        // no source name or source text manager, because we're just
        // going to throw away the result.
        TokenStream ts = new TokenStream(in, null, null, 1);

        // Temporarily set error reporter to always be the exception-throwing
        // DefaultErrorReporter.  (This is why the method is synchronized...)
        DeepErrorReporterHook hook = setErrorReporterHook(null);
        ErrorReporter currentReporter = 
            setErrorReporter(new DefaultErrorReporter());

        boolean errorseen = false;
        try {
            IRFactory irf = new IRFactory(ts, null);
            Parser p = new Parser(irf);
            p.parse(ts);
        } catch (IOException ioe) {
            errorseen = true;
        } catch (EvaluatorException ee) {
            errorseen = true;
        } finally {
            // Restore the old error reporter.
            setErrorReporter(currentReporter);
            setErrorReporterHook(hook);
        }
        // Return false only if an error occurred as a result of reading past
        // the end of the file, i.e. if the source could be fixed by
        // appending more source.
        if (errorseen && ts.eof())
            return false;
        else 
            return true;
    }

    /**
     * Compiles the source in the given reader.
     * <p>
     * Returns a script that may later be executed.
     * Will consume all the source in the reader.
     *
     * @param scope if nonnull, will be the scope in which the script object
     *        is created. The script object will be a valid JavaScript object
     *        as if it were created using the JavaScript1.3 Script constructor
     * @param in the input reader
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number for reporting errors
     * @param securityDomain an arbitrary object that specifies security 
     *        information about the origin or owner of the script. For 
     *        implementations that don't care about security, this value 
     *        may be null.
     * @return a script that may later be executed
     * @see org.mozilla.javascript.Script#exec
     * @exception IOException if an IOException was generated by the Reader
     */
    public Script compileReader(Scriptable scope, Reader in, String sourceName,
                                int lineno, Object securityDomain)
        throws IOException
    {
        return (Script) compile(scope, in, sourceName, lineno, securityDomain, 
                                false);
    }


    /**
     * Compile a JavaScript function.
     * <p>
     * The function source must be a function definition as defined by
     * ECMA (e.g., "function f(a) { return a; }"). 
     *
     * @param scope the scope to compile relative to
     * @param source the function definition source
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number
     * @param securityDomain an arbitrary object that specifies security 
     *        information about the origin or owner of the script. For 
     *        implementations that don't care about security, this value 
     *        may be null.
     * @return a Function that may later be called
     * @see org.mozilla.javascript.Function
     */
    public Function compileFunction(Scriptable scope, String source,
                                    String sourceName, int lineno,
                                    Object securityDomain)
    {
        Reader in = new StringReader(source);
        try {
            return (Function) compile(scope, in, sourceName, lineno, 
                                      securityDomain, true);
        }
        catch (IOException ioe) {
            // Should never happen because we just made the reader
            // from a String
            throw new RuntimeException();
        }
    }

    /**
     * Decompile the script.
     * <p>
     * The canonical source of the script is returned.
     *
     * @param script the script to decompile
     * @param scope the scope under which to decompile
     * @param indent the number of spaces to indent the result
     * @return a string representing the script source
     */
     public String decompileScript(Script script, Scriptable scope,
                                   int indent)
    {
        NativeScript ns = (NativeScript) script;
        ns.initScript(scope);
        return ns.decompile(indent, true, false);
    }

    /**
     * Decompile a JavaScript Function.
     * <p>
     * Decompiles a previously compiled JavaScript function object to
     * canonical source.
     * <p>
     * Returns function body of '[native code]' if no decompilation
     * information is available.
     *
     * @param fun the JavaScript function to decompile
     * @param indent the number of spaces to indent the result
     * @return a string representing the function source
     */
    public String decompileFunction(Function fun, int indent) {
        if (fun instanceof NativeFunction)
            return ((NativeFunction)fun).decompile(indent, true, false);
        else
            return "function " + fun.getClassName() +
                   "() {\n\t[native code]\n}\n";
    }

    /**
     * Decompile the body of a JavaScript Function.
     * <p>
     * Decompiles the body a previously compiled JavaScript Function
     * object to canonical source, omitting the function header and
     * trailing brace.
     *
     * Returns '[native code]' if no decompilation information is available.
     *
     * @param fun the JavaScript function to decompile
     * @param indent the number of spaces to indent the result
     * @return a string representing the function body source.
     */
    public String decompileFunctionBody(Function fun, int indent) {
        if (fun instanceof NativeFunction)
            return ((NativeFunction)fun).decompile(indent, true, true);
        else
            // not sure what the right response here is.  JSRef currently
            // dumps core.
            return "[native code]\n";
    }

    /**
     * Create a new JavaScript object.
     *
     * Equivalent to evaluating "new Object()".
     * @param scope the scope to search for the constructor and to evaluate
     *              against
     * @return the new object
     * @exception PropertyException if "Object" cannot be found in
     *            the scope
     * @exception NotAFunctionException if the "Object" found in the scope
     *            is not a function
     * @exception JavaScriptException if an uncaught JavaScript exception
     *            occurred while creating the object
     */
    public Scriptable newObject(Scriptable scope)
        throws PropertyException,
               NotAFunctionException,
               JavaScriptException
    {
        return newObject(scope, "Object", null);
    }

    /**
     * Create a new JavaScript object by executing the named constructor.
     *
     * The call <code>newObject("Foo")</code> is equivalent to
     * evaluating "new Foo()".
     *
     * @param scope the scope to search for the constructor and to evaluate against
     * @param constructorName the name of the constructor to call
     * @return the new object
     * @exception PropertyException if a property with the constructor
     *            name cannot be found in the scope
     * @exception NotAFunctionException if the property found in the scope
     *            is not a function
     * @exception JavaScriptException if an uncaught JavaScript exception
     *            occurred while creating the object
     */
    public Scriptable newObject(Scriptable scope, String constructorName)
        throws PropertyException,
               NotAFunctionException,
               JavaScriptException
    {
        return newObject(scope, constructorName, null);
    }

    /**
     * Creates a new JavaScript object by executing the named constructor.
     *
     * Searches <code>scope</code> for the named constructor, calls it with
     * the given arguments, and returns the result.<p>
     *
     * The code
     * <pre>
     * Object[] args = { "a", "b" };
     * newObject(scope, "Foo", args)</pre>
     * is equivalent to evaluating "new Foo('a', 'b')", assuming that the Foo
     * constructor has been defined in <code>scope</code>.
     *
     * @param scope The scope to search for the constructor and to evaluate
     *              against
     * @param constructorName the name of the constructor to call
     * @param args the array of arguments for the constructor
     * @return the new object
     * @exception PropertyException if a property with the constructor
     *            name cannot be found in the scope
     * @exception NotAFunctionException if the property found in the scope
     *            is not a function
     * @exception JavaScriptException if an uncaught JavaScript exception
     *            occurs while creating the object
     */
    public Scriptable newObject(Scriptable scope, String constructorName,
                                Object[] args)
        throws PropertyException,
               NotAFunctionException,
               JavaScriptException
    {
        Object ctorVal = ScriptRuntime.getTopLevelProp(scope, constructorName);
        if (ctorVal == Scriptable.NOT_FOUND) {
            Object[] errArgs = { constructorName };
            String message = getMessage("msg.ctor.not.found", errArgs);
            throw new PropertyException(message);
        }
        if (!(ctorVal instanceof Function)) {
            Object[] errArgs = { constructorName };
            String message = getMessage("msg.not.ctor", errArgs);
            throw new NotAFunctionException(message);
        }
        Function ctor = (Function) ctorVal;
        return ctor.construct(this, ctor.getParentScope(),
                              (args == null) ? ScriptRuntime.emptyArgs : args);
    }

    /**
     * Create an array with a specified initial length.
     * <p>
     * @param scope the scope to create the object in
     * @param length the initial length (JavaScript arrays may have
     *               additional properties added dynamically).
     * @return the new array object
     */
    public Scriptable newArray(Scriptable scope, int length) {
        Scriptable result = new NativeArray(length);
        newArrayHelper(scope, result);
        return result;
    }

    /**
     * Create an array with a set of initial elements.
     * <p>
     * @param scope the scope to create the object in
     * @param elements the initial elements. Each object in this array
     *                 must be an acceptable JavaScript type.
     * @return the new array object
     */
    public Scriptable newArray(Scriptable scope, Object[] elements) {
        Scriptable result = new NativeArray(elements);
        newArrayHelper(scope, result);
        return result;
    }
    
    /**
     * Get the elements of a JavaScript array.
     * <p>
     * If the object defines a length property, a Java array with that
     * length is created and initialized with the values obtained by
     * calling get() on object for each value of i in [0,length-1]. If
     * there is not a defined value for a property the Undefined value
     * is used to initialize the corresponding element in the array. The
     * Java array is then returned.
     * If the object doesn't define a length property, null is returned.
     * @param object the JavaScript array or array-like object
     * @return a Java array of objects
     * @since 1.4 release 2
     */
    public Object[] getElements(Scriptable object) {
        double doubleLen = NativeArray.getLengthProperty(object);
        if (doubleLen != doubleLen)
            return null;
        int len = (int) doubleLen;
        Object[] result = new Object[len];
        for (int i=0; i < len; i++) {
            Object elem = object.get(i, object);
            result[i] = elem == Scriptable.NOT_FOUND ? Undefined.instance 
                                                     : elem;
        }
        return result;
    }

    /**
     * Convert the value to a JavaScript boolean value.
     * <p>
     * See ECMA 9.2.
     *
     * @param value a JavaScript value
     * @return the corresponding boolean value converted using
     *         the ECMA rules
     */
    public static boolean toBoolean(Object value) {
        return ScriptRuntime.toBoolean(value);
    }

    /**
     * Convert the value to a JavaScript Number value.
     * <p>
     * Returns a Java double for the JavaScript Number.
     * <p>
     * See ECMA 9.3.
     *
     * @param value a JavaScript value
     * @return the corresponding double value converted using
     *         the ECMA rules
     */
    public static double toNumber(Object value) {
        return ScriptRuntime.toNumber(value);
    }

    /**
     * Convert the value to a JavaScript String value.
     * <p>
     * See ECMA 9.8.
     * <p>
     * @param value a JavaScript value
     * @return the corresponding String value converted using
     *         the ECMA rules
     */
    public static String toString(Object value) {
        return ScriptRuntime.toString(value);
    }

    /**
     * Convert the value to an JavaScript object value.
     * <p>
     * Note that a scope must be provided to look up the constructors
     * for Number, Boolean, and String.
     * <p>
     * See ECMA 9.9.
     * <p>
     * Additionally, arbitrary Java objects and classes will be
     * wrapped in a Scriptable object with its Java fields and methods
     * reflected as JavaScript properties of the object.
     *
     * @param value any Java object
     * @param scope global scope containing constructors for Number,
     *              Boolean, and String
     * @return new JavaScript object
     */
    public static Scriptable toObject(Object value, Scriptable scope) {
        return ScriptRuntime.toObject(scope, value, null);
    }
    
    /**
     * Convert the value to an JavaScript object value.
     * <p>
     * Note that a scope must be provided to look up the constructors
     * for Number, Boolean, and String.
     * <p>
     * See ECMA 9.9.
     * <p>
     * Additionally, arbitrary Java objects and classes will be
     * wrapped in a Scriptable object with its Java fields and methods
     * reflected as JavaScript properties of the object. If the 
     * "staticType" parameter is provided, it will be used as the static
     * type of the Java value to create.
     *
     * @param value any Java object
     * @param scope global scope containing constructors for Number,
     *              Boolean, and String
     * @param staticType the static type of the Java value to create
     * @return new JavaScript object
     */
    public static Scriptable toObject(Object value, Scriptable scope, 
                                      Class staticType) {
        if (value == null && staticType != null)
            return null;
        return ScriptRuntime.toObject(scope, value, staticType);
    }

    /**
     * Tell whether debug information is being generated.
     * @since 1.3
     */
    public boolean isGeneratingDebug() {
        return generatingDebug;
    }

    /**
     * Specify whether or not debug information should be generated.
     * <p>
     * Setting the generation of debug information on will set the
     * optimization level to zero.
     * @since 1.3
     */
    public void setGeneratingDebug(boolean generatingDebug) {
        if (generatingDebug)
            setOptimizationLevel(0);
        this.generatingDebug = generatingDebug;
    }

    /**
     * Tell whether source information is being generated.
     * @since 1.3
     */
    public boolean isGeneratingSource() {
        return generatingSource;
    }

    /**
     * Specify whether or not source information should be generated.
     * <p>
     * Without source information, evaluating the "toString" method
     * on JavaScript functions produces only "[native code]" for
     * the body of the function.
     * Note that code generated without source is not fully ECMA
     * conformant.
     * @since 1.3
     */
    public void setGeneratingSource(boolean generatingSource) {
        this.generatingSource = generatingSource;
    }

    /**
     * Get the current optimization level.
     * <p>
     * The optimization level is expressed as an integer between -1 and
     * 9.
     * @since 1.3
     *
     */
    public int getOptimizationLevel() {
        return optimizationLevel;
    }

    /**
     * Set the current optimization level.
     * <p>
     * The optimization level is expected to be an integer between -1 and
     * 9. Any negative values will be interpreted as -1, and any values
     * greater than 9 will be interpreted as 9.
     * An optimization level of -1 indicates that interpretive mode will
     * always be used. Levels 0 through 9 indicate that class files may 
     * be generated. Higher optimization levels trade off compile time
     * performance for runtime performance.
     * The optimizer level can't be set greater than -1 if the optimizer
     * package doesn't exist at run time.
     * @param optimizationLevel an integer indicating the level of
     *        optimization to perform
     * @since 1.3
     *
     */
    public void setOptimizationLevel(int optimizationLevel) {
        if (optimizationLevel < 0) {
            optimizationLevel = -1;
        } else if (optimizationLevel > 9) {
                optimizationLevel = 9;
        }
        if (codegenClass == null)
            optimizationLevel = -1;
        this.optimizationLevel = optimizationLevel;
    }

    /**
     * Get the current target class file name.
     * <p>
     * If nonnull, requests to compile source will result in one or
     * more class files being generated.
     * @since 1.3
     */
    public String getTargetClassFileName() {
        return nameHelper == null
               ? null 
               : nameHelper.getTargetClassFileName();
    }

    /**
     * Set the current target class file name.
     * <p>
     * If nonnull, requests to compile source will result in one or
     * more class files being generated. If null, classes will only
     * be generated in memory.
     *
     * @since 1.3
     */
    public void setTargetClassFileName(String classFileName) {
        if (nameHelper != null)
            nameHelper.setTargetClassFileName(classFileName);
    }

    /**
     * Get the current package to generate classes into.
     *
     * @since 1.3
     */
    public String getTargetPackage() {
        return (nameHelper == null) ? null : nameHelper.getTargetPackage();
    }

    /**
     * Set the package to generate classes into.
     *
     * @since 1.3
     */
    public void setTargetPackage(String targetPackage) {
        if (nameHelper != null)
            nameHelper.setTargetPackage(targetPackage);
    }

    /**
     * Get the current interface to write class bytes into.
     *
     * @see ClassOutput
     * @since 1.5 Release 2
     */
    public ClassOutput getClassOutput() {
        return nameHelper == null ? null : nameHelper.getClassOutput();
    }

    /**
     * Set the interface to write class bytes into.
     * Unless setTargetClassFileName() has been called classOutput will be
     * used each time the javascript compiler has generated the bytecode for a
     * script class.
     *
     * @see ClassOutput
     * @since 1.5 Release 2
     */
    public void setClassOutput(ClassOutput classOutput) {
        if (nameHelper != null)
            nameHelper.setClassOutput(classOutput);
    }
        
    /**
     * Return true if a security domain is required on calls to
     * compile and evaluate scripts.
     *
     * @since 1.4 Release 2
     */
    public static boolean isSecurityDomainRequired() { 
        return requireSecurityDomain;
    }
    
    /**
     * Returns the security context associated with the innermost
     * script or function being executed by the interpreter.
     * @since 1.4 release 2
     */
    public Object getInterpreterSecurityDomain() {
        return interpreterSecurityDomain;
    }
    
    /**
     * Returns true if the class parameter is a class in the 
     * interpreter. Typically used by embeddings that get a class
     * context to check security. These embeddings must know 
     * whether to get the security context associated with the
     * interpreter or not.
     * 
     * @param cl a class to test whether or not it is an interpreter
     *           class
     * @return true if cl is an interpreter class
     * @since 1.4 release 2
     */
    public boolean isInterpreterClass(Class cl) {
        return cl == Interpreter.class;
    }
    
    /**
     * Set the class that the generated target will extend.
     * 
     * @param extendsClass the class it extends
     */
    public void setTargetExtends(Class extendsClass) {
        if (nameHelper != null) {
            nameHelper.setTargetExtends(extendsClass);
        }
    }
       
    /**
     * Set the interfaces that the generated target will implement.
     * 
     * @param implementsClasses an array of Class objects, one for each
     *                          interface the target will extend
     */
    public void setTargetImplements(Class[] implementsClasses) {
        if (nameHelper != null) {
            nameHelper.setTargetImplements(implementsClasses);
        }
    }
        
    /**
     * Get a value corresponding to a key.
     * <p>
     * Since the Context is associated with a thread it can be 
     * used to maintain values that can be later retrieved using 
     * the current thread. 
     * <p>
     * Note that the values are maintained with the Context, so
     * if the Context is disassociated from the thread the values
     * cannot be retreived. Also, if private data is to be maintained
     * in this manner the key should be a java.lang.Object 
     * whose reference is not divulged to untrusted code.
     * @param key the key used to lookup the value
     * @return a value previously stored using putThreadLocal.
     */
    public Object getThreadLocal(Object key) {
        if (hashtable == null)
            return null;
        return hashtable.get(key);
    }

    /**
     * Put a value that can later be retrieved using a given key.
     * <p>
     * @param key the key used to index the value
     * @param value the value to save
     */
    public void putThreadLocal(Object key, Object value) {
        if (hashtable == null)
            hashtable = new Hashtable();
        hashtable.put(key, value);
    }
    
    /**
     * Return whether functions are compiled by this context using
     * dynamic scope.
     * <p>
     * If functions are compiled with dynamic scope, then they execute
     * in the scope of their caller, rather than in their parent scope.
     * This is useful for sharing functions across multiple scopes.
     * @since 1.5 Release 1
     */
    public boolean hasCompileFunctionsWithDynamicScope() {
        return compileFunctionsWithDynamicScopeFlag;
    }
    
    /**
     * Set whether functions compiled by this context should use
     * dynamic scope.
     * <p>
     * @param flag if true, compile functions with dynamic scope
     * @since 1.5 Release 1
     */
    public void setCompileFunctionsWithDynamicScope(boolean flag) {
        compileFunctionsWithDynamicScopeFlag = flag;
    }
    
    /**
     * Set whether to cache some values statically.
     * <p>
     * By default, the engine will cache some values statically 
     * (reflected Java classes, for instance). This can speed
     * execution dramatically, but increases the memory footprint.
     * Also, with caching enabled, references may be held to 
     * objects past the lifetime of any real usage. 
     * <p>
     * If caching is enabled and this method is called with a 
     * <code>false</code> argument, the caches will be emptied.
     * So one strategy could be to clear the caches at times
     * appropriate to the application.
     * <p>
     * Caching is enabled by default.
     * 
     * @param cachingEnabled if true, caching is enabled
     * @since 1.5 Release 1 
     */
    public static void setCachingEnabled(boolean cachingEnabled) {
        if (isCachingEnabled && !cachingEnabled) {
            // Caching is being turned off. Empty caches.
            FunctionObject.methodsCache = null;
            JavaMembers.classTable = new Hashtable();
        }
        isCachingEnabled = cachingEnabled;
    }
    
    /**
     */
    public void setWrapHandler(WrapHandler wrapHandler) {
        this.wrapHandler = wrapHandler;
    }
    
    public WrapHandler getWrapHandler() {
        return wrapHandler;
    }

    /**** debugger oriented portion of API: CURRENTLY UNSUPPORTED ****/

    /**
     * Get the current source text hook (for debugging).
     * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
     * @return the current hook
     * @see org.mozilla.javascript.SourceTextManager
     * @see org.mozilla.javascript.Context#setSourceTextManager
     * @deprecated
     */
    public SourceTextManager getSourceTextManager() {
        return debug_stm;
    }

    /**
     * Set the current source text hook (for debugging).
     * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
     * <p>
     * When using the org.mozilla.javascript.debug system to debug within the
     * context of a particular embedding if the Context has this hook set
     * then all parsed JavaScript source will be passed to the hook. In
     * some embeddings of JavaScript it may be  better to not use this
     * low level hook and instead have the embedding itself feed the
     * source text to the SourceTextManager.
     *
     * @param debug_stm new hook
     * @return the previous hook
     * @see org.mozilla.javascript.SourceTextManager
     */
    public SourceTextManager setSourceTextManager(SourceTextManager debug_stm) {
        SourceTextManager result = this.debug_stm;
        this.debug_stm = debug_stm;
        return result;
    }

    /**
     * Get the current script hook (for debugging).
     * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
     *
     * @return the current hook
     * @see org.mozilla.javascript.DeepScriptHook
     * @see org.mozilla.javascript.Context#setScriptHook
     */
    public DeepScriptHook getScriptHook() {
        return debug_scriptHook;
    }

    /**
     * Set the current script hook (for debugging).
     * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
     * <p>
     * At debugLevel >= 3 the script hook is called when
     * compiled scripts (and functions) are loaded and unloaded.
     *
     * @param hook new hook
     * @return the previous hook
     * @see org.mozilla.javascript.DeepScriptHook
     */
    public DeepScriptHook setScriptHook(DeepScriptHook hook) {
        DeepScriptHook result = debug_scriptHook;
        debug_scriptHook = hook;
        return result;
    }

    /**
     * Get the current call hook (for debugging).
     * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
     *
     * @return the current hook
     * @see org.mozilla.javascript.DeepCallHook
     * @see org.mozilla.javascript.Context#setCallHook
     */
    public DeepCallHook getCallHook() {
        return debug_callHook;
    }

    /**
     * Set the current call hook (for debugging).
     * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
     * <p>
     * At debugLevel >= 3 the call hook is called when
     * compiled scripts and functions make function calls.
     *
     * @param hook new hook
     * @return the previous hook
     * @see org.mozilla.javascript.DeepCallHook
     */
    public DeepCallHook setCallHook(DeepCallHook hook) {
        DeepCallHook result = debug_callHook;
        debug_callHook = hook;
        return result;
    }

    /**
     * Get the current execute hook (for debugging).
      * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
    *
     * @return the current hook
     * @see org.mozilla.javascript.DeepExecuteHook
     * @see org.mozilla.javascript.Context#setExecuteHook
     */
    public DeepExecuteHook getExecuteHook() {
        return debug_executeHook;
    }

    /**
     * Set the current execute hook (for debugging).
     * <p>
     * At debugLevel >= 3 the execute hook is called when
     * top level compiled scripts (non-functions) are executed.
     *
     * @param hook new hook
     * @return the previous hook
     * @see org.mozilla.javascript.DeepExecuteHook
     */
    public DeepExecuteHook setExecuteHook(DeepExecuteHook hook) {
        DeepExecuteHook result = debug_executeHook;
        debug_executeHook = hook;
        return result;
    }

    /**
     * Get the current new object hook (for debugging).
     * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
     *
     * @return the current hook
     * @see org.mozilla.javascript.DeepNewObjectHook
     * @see org.mozilla.javascript.Context#setNewObjectHook
     */
    public DeepNewObjectHook getNewObjectHook() {
        return debug_newObjectHook;
    }

    /**
     * Set the current new object hook (for debugging).
     * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
     * <p>
     * At debugLevel >= 3 the new object hook is called when
     * JavaScript objects are created by compiled scripts
     * and functions; i.e. when constructor functions run.
     *
     * @param hook new hook
     * @return the previous hook
     * @see org.mozilla.javascript.DeepNewObjectHook
     */
    public DeepNewObjectHook setNewObjectHook(DeepNewObjectHook hook) {
        DeepNewObjectHook result = debug_newObjectHook;
        debug_newObjectHook = hook;
        return result;
    }

    /**
     * Get the current byte code hook (for debugging).
     * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
     *
     * @return the current hook
     * @see org.mozilla.javascript.DeepBytecodeHook
     * @see org.mozilla.javascript.Context#setBytecodeHook
     */
    public DeepBytecodeHook getBytecodeHook() {
        return debug_bytecodeHook;
    }

    /**
     * Set the current byte code hook (for debugging).
     * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
     * <p>
     * At debugLevel >= 6 generated scripts and functions
     * support setting traps and interrupts on a per statement
     * basis. If a trap or interrupt is encountered while
     * running in this Context, then this hook is called to
     * handle it.
     *
     * @param hook new hook
     * @return the previous hook
     * @see org.mozilla.javascript.DeepBytecodeHook
     */
    public DeepBytecodeHook setBytecodeHook(DeepBytecodeHook hook) {
        DeepBytecodeHook result = debug_bytecodeHook;
        debug_bytecodeHook = hook;
        return result;
    }

    /**
     * Get the current error reporter hook (for debugging).
      * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
    *
     * @return the current hook
     * @see org.mozilla.javascript.DeepErrorReporterHook
     * @see org.mozilla.javascript.Context#setErrorReporter
     */
    public DeepErrorReporterHook getErrorReporterHook() {
        return debug_errorReporterHook;
    }

    /**
     * Set the current error reporter hook (for debugging).
     * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
     * <p>
     * This hook allows a debugger to trap error reports before
     * there are sent to the error reporter. This is not meant to
     * be used in place of the normal error reporting system.
     *
     * @param hook new hook
     * @return the previous hook
     * @see org.mozilla.javascript.DeepErrorReporterHook
     * @see org.mozilla.javascript.ErrorReporter
     * @see org.mozilla.javascript.Context#setErrorReporter
     */
    public DeepErrorReporterHook setErrorReporterHook(DeepErrorReporterHook hook) {
        DeepErrorReporterHook result = debug_errorReporterHook;
        debug_errorReporterHook = hook;
        return result;
    }

    /**
     * Get the current debug level (for debugging).
     * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
     *
     * @return the current debug level
     * @see org.mozilla.javascript.Context#setDebugLevel
     */
    public int getDebugLevel() {
        return debugLevel;
    }

    /**
     * Set the current debug level (for debugging).
     * <p>
     * <b>CURRRENTLY UNSUPPORTED.</b>
     * <p>
     * Set the debug level. Note that a non-zero debug level will
     * force the optimization level to 0.
     * <p>
     * Currently supported debug levels:
     * <pre>
     *  debugLevel == 0 - all debug support off (except error reporter hooks)
     *  debugLevel >= 1 - name of source file stored in NativeFunction
     *  debugLevel >= 3 - load/unload hooks called
     *                  - base/end lineno info stored in NativeFunction
     *                  - call, new object, and execute hooks called
     *  debugLevel >= 6 - interrupts and traps supported
     *
     * </pre>
     *
     * @param debugLevel new debugLevel
     * @return the previous debug level
     */
    public int setDebugLevel(int debugLevel) {
        int result = this.debugLevel;
        if (debugLevel < 0)
            debugLevel = 0;
        else if (debugLevel > 9)
            debugLevel = 9;
        if (debugLevel > 0)
            setOptimizationLevel(0);
        return result;
    }
    
    /********** end of API **********/

    /**
     * Internal method that reports an error for missing calls to
     * enter().
     */
    static Context getContext() {
        Thread t = Thread.currentThread();
        Context cx = (Context) threadContexts.get(t);
        if (cx == null) {
            throw new RuntimeException(
                "No Context associated with current Thread");
        }
        return cx;
    }

    /* OPT there's a noticable delay for the first error!  Maybe it'd
     * make sense to use a ListResourceBundle instead of a properties
     * file to avoid (synchronized) text parsing.
     */
    static final String defaultResource = 
        "org.mozilla.javascript.resources.Messages";

    static String getMessage(String messageId, Object[] arguments) {
        Context cx = getCurrentContext();
        Locale locale = cx != null ? cx.getLocale() : Locale.getDefault();

        // ResourceBundle does cacheing.
        ResourceBundle rb = ResourceBundle.getBundle(defaultResource, locale);

        String formatString;
        try {
            formatString = rb.getString(messageId);
        } catch (java.util.MissingResourceException mre) {
            throw new RuntimeException
                ("no message resource found for message property "+ messageId);
        }

        /*
         * It's OK to format the string, even if 'arguments' is null;
         * we need to format it anyway, to make double ''s collapse to
         * single 's.
         */
        // TODO: MessageFormat is not available on pJava
        MessageFormat formatter = new MessageFormat(formatString);
        return formatter.format(arguments);
    }

    // debug flags
    static final boolean printTrees = false;
    
    /**
     * Compile a script.
     *
     * Reads script source from the reader and compiles it, returning
     * a class for either the script or the function depending on the
     * value of <code>returnFunction</code>.
     *
     * @param scope the scope to compile relative to
     * @param in the Reader to read source from
     * @param sourceName the name of the origin of the source (usually
     *                   a file or URL)
     * @param lineno the line number of the start of the source
     * @param securityDomain an arbitrary object that specifies security 
     *        information about the origin or owner of the script. For 
     *        implementations that don't care about security, this value 
     *        may be null.
     * @param returnFunction if true, will expect the source to contain
     *                        a function; return value is assumed to
     *                        then be a org.mozilla.javascript.Function
     * @return a class for the script or function
     * @see org.mozilla.javascript.Context#compileReader
     */
    private Object compile(Scriptable scope, Reader in, String sourceName, 
                           int lineno, Object securityDomain, 
                           boolean returnFunction)
        throws IOException
    {
        TokenStream ts = new TokenStream(in, scope, sourceName, lineno);
        return compile(scope, ts, securityDomain, returnFunction);
    }

    private static Class codegenClass;
    private static ClassNameHelper nameHelper;
    static {
        try {
            codegenClass = Class.forName(
                "org.mozilla.javascript.optimizer.Codegen");
            Class nameHelperClass = Class.forName(
                "org.mozilla.javascript.optimizer.OptClassNameHelper");
            nameHelper = (ClassNameHelper)nameHelperClass.newInstance();
        } catch (ClassNotFoundException x) {
            // ...must be running lite, that's ok
            codegenClass = null;
        } catch (IllegalAccessException x) {
            codegenClass = null;
        } catch (InstantiationException x) {
            codegenClass = null;
        }
    }
    
    private Interpreter getCompiler() {
        if (codegenClass == null) {
            return new Interpreter();
        } else {
            try {
                return (Interpreter) codegenClass.newInstance();
            }
            catch (SecurityException x) {   
            }
            catch (IllegalArgumentException x) {
            }
            catch (InstantiationException x) {
            }
            catch (IllegalAccessException x) {
            }
            throw new RuntimeException("Malformed optimizer package");
        }
    }
    
    private Object compile(Scriptable scope, TokenStream ts, 
                           Object securityDomain, boolean returnFunction)
        throws IOException
    {
        Interpreter compiler = optimizationLevel == -1 
                               ? new Interpreter()
                               : getCompiler();
        
        errorCount = 0;
        IRFactory irf = compiler.createIRFactory(ts, nameHelper, scope);
        Parser p = new Parser(irf);
        Node tree = (Node) p.parse(ts);
        if (tree == null) 
            return null;

        tree = compiler.transform(tree, ts, scope);

        if (printTrees)
            System.out.println(tree.toStringTree());
        
        if (returnFunction) {
            Node first = tree.getFirstChild();
            if (first == null)
                return null;
            tree = (Node) first.getProp(Node.FUNCTION_PROP);
            if (tree == null)
                return null;
        }

        Object result = compiler.compile(this, scope, tree, securityDomain,
                                         securitySupport, nameHelper);

        return errorCount == 0 ? result : null;
    }

    /**
     * A bit of a hack, but the only way to get filename and line
     * number from an enclosing frame.
     */
    static String getSourcePositionFromStack(int[] linep) {
        CharArrayWriter writer = new CharArrayWriter();
        RuntimeException re = new RuntimeException();
        re.printStackTrace(new PrintWriter(writer));
        String s = writer.toString();
        int open = -1;
        int close = -1;
        int colon = -1;
        for (int i=0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':')
                colon = i;
            else if (c == '(')
                open = i;
            else if (c == ')')
                close = i;
            else if (c == '\n' && open != -1 && close != -1 && colon != -1) {
                String fileStr = s.substring(open + 1, colon);
                if (fileStr.endsWith(".js")) {
                    String lineStr = s.substring(colon + 1, close);
                    try {
                        linep[0] = Integer.parseInt(lineStr);
                        return fileStr;
                    }
                    catch (NumberFormatException e) {
                        // fall through
                    }
                }
                open = close = colon = -1;
            }
        }

        // Not found; so we should try the interpreter data.
        Context cx = getCurrentContext();
        if (cx.interpreterLine > 0 && cx.interpreterSourceFile != null) {
            linep[0] = cx.interpreterLine;
            return cx.interpreterSourceFile;
        }
        return null;
    }

    RegExpProxy getRegExpProxy() {
        if (regExpProxy == null) {
            try {
                Class c = Class.forName(
                            "org.mozilla.javascript.regexp.RegExpImpl");
                regExpProxy = (RegExpProxy) c.newInstance();
                return regExpProxy;
            } catch (ClassNotFoundException e) {
            } catch (InstantiationException e) {
            } catch (IllegalAccessException e) {
            }
        }
        return regExpProxy;
    }

    private void newArrayHelper(Scriptable scope, Scriptable array) {
        array.setParentScope(scope);
        Object ctor = ScriptRuntime.getTopLevelProp(scope, "Array");
        if (ctor != null && ctor instanceof Scriptable) {
            Scriptable s = (Scriptable) ctor;
            array.setPrototype((Scriptable) s.get("prototype", s));
        }
    }
    
    final boolean isVersionECMA1() {
        return version == VERSION_DEFAULT || version >= VERSION_1_3;
    }

    /**
     * Get the security context from the given class.
     * <p>
     * When some form of security check needs to be done, the class context
     * must retrieved from the security manager to determine what class is
     * requesting some form of privileged access. 
     * @since 1.4 release 2
     */
    Object getSecurityDomainFromClass(Class cl) {
        if (cl == Interpreter.class)
            return interpreterSecurityDomain;
        return securitySupport.getSecurityDomain(cl);
    }
    
    SecuritySupport getSecuritySupport() {
        return securitySupport;
    }
    
    Object getSecurityDomainForStackDepth(int depth) {
        Object result = null;
        if (securitySupport != null) {
            Class[] classes = securitySupport.getClassContext();
            if (depth != -1) {
                int depth1 = depth + 1;
                result = getSecurityDomainFromClass(classes[depth1]);
            } else {
                for (int i=1; i < classes.length; i++) {
                    result = getSecurityDomainFromClass(classes[i]);
                    if (result != null)
                        break;
                }
            }
        }
        if (result != null)
            return result;
        if (requireSecurityDomain)
            throw new SecurityException("Required security context not found");
        return null;
    }

    private static boolean requireSecurityDomain = true;
    static {
        final String securityResourceName = 
            "org.mozilla.javascript.resources.Security";
        try {
            ResourceBundle rb = ResourceBundle.getBundle(securityResourceName);
            String s = rb.getString("security.requireSecurityDomain");
            requireSecurityDomain = s.equals("true");
        } catch (java.util.MissingResourceException mre) {
            requireSecurityDomain = true;
        } catch (SecurityException se) {
            requireSecurityDomain = true;
        }   
    }      
    
    static final boolean useJSObject = false;

    /** 
     * The activation of the currently executing function or script. 
     */
    NativeCall currentActivation;

    // for Objects, Arrays to tag themselves as being printed out,
    // so they don't print themselves out recursively.
    Hashtable iterating;
            
    Object interpreterSecurityDomain;
    Scriptable ctorScope;

    int version;
    int errorCount;
    static boolean isCachingEnabled = true;
    
    private SecuritySupport securitySupport;
    private ErrorReporter errorReporter;
    private Thread currentThread;
    private static Hashtable threadContexts = new Hashtable(11);
    private RegExpProxy regExpProxy;
    private Locale locale;
    private boolean generatingDebug;
    private boolean generatingSource=true;
    private boolean compileFunctionsWithDynamicScopeFlag;
    private int optimizationLevel;
    WrapHandler wrapHandler;
    private SourceTextManager debug_stm;
    private DeepScriptHook debug_scriptHook;
    private DeepCallHook debug_callHook;
    private DeepExecuteHook debug_executeHook;
    private DeepNewObjectHook debug_newObjectHook;
    private DeepBytecodeHook debug_bytecodeHook;
    private DeepErrorReporterHook debug_errorReporterHook;
    // debugging is currently unsupported, leave the code in for now
    // in case we get a chance to revive it
    private static final byte debugLevel = 0;
    private int enterCount;
    private ListenerCollection listeners;
    private Hashtable hashtable;

    // For the interpreter to indicate line/source for error reports.
    int interpreterLine;
    String interpreterSourceFile;
}
