/*
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


package org.mozilla.javascript.optimizer;

import org.mozilla.javascript.*;
import org.mozilla.classfile.*;
import java.util.*;
import java.io.IOException;
import java.lang.reflect.Constructor;

/**
 * This class generates code for a given IR tree.
 *
 * @author Norris Boyd
 * @author Roger Lawrence
 */

public class Codegen extends Interpreter {

    public Codegen()
    {
        mainCodegen = this;
        isMainCodegen = true;
    }

    private Codegen(Codegen mainCodegen)
    {
        if (mainCodegen == null) Context.codeBug();
        this.mainCodegen = mainCodegen;
        isMainCodegen = false;
    }

    public IRFactory createIRFactory(Context cx, TokenStream ts)
    {
        if (nameHelper == null) {
            nameHelper = (OptClassNameHelper)ClassNameHelper.get(cx);
            classNames = new ObjToIntMap();
        }
        return new IRFactory(this, ts);
    }

    public FunctionNode createFunctionNode(IRFactory irFactory, String name)
    {
        String className = getScriptClassName(name, false);
        return new OptFunctionNode(name, className);
    }

    public ScriptOrFnNode transform(Context cx, IRFactory irFactory,
                                    ScriptOrFnNode tree)
    {
        int optLevel = cx.getOptimizationLevel();
        Hashtable possibleDirectCalls = null;
        if (optLevel > 0) {
           /*
            * Collect all of the contained functions into a hashtable
            * so that the call optimizer can access the class name & parameter
            * count for any call it encounters
            */
            if (tree.getType() == TokenStream.SCRIPT) {
                int functionCount = tree.getFunctionCount();
                for (int i = 0; i != functionCount; ++i) {
                    OptFunctionNode fn;
                    fn = (OptFunctionNode)tree.getFunctionNode(i);
                    if (fn.getFunctionType()
                        == FunctionNode.FUNCTION_STATEMENT)
                    {
                        String name = fn.getFunctionName();
                        if (name.length() != 0) {
                            if (possibleDirectCalls == null) {
                                possibleDirectCalls = new Hashtable();
                            }
                            possibleDirectCalls.put(name, fn);
                        }
                    }
                }
            }
        }

        if (possibleDirectCalls != null) {
            directCallTargets = new ObjArray();
        }

        OptTransformer ot = new OptTransformer(irFactory, possibleDirectCalls,
                                               directCallTargets);
        ot.transform(tree);

        if (optLevel > 0) {
            (new Optimizer(irFactory)).optimize(tree, optLevel);
        }

        return tree;
    }

    public Object compile(Context cx, Scriptable scope,
                          ScriptOrFnNode scriptOrFn,
                          SecurityController securityController,
                          Object securityDomain)
    {
        ObjArray classFiles = new ObjArray();
        ObjArray names = new ObjArray();
        generateCode(cx, scriptOrFn, names, classFiles);

        boolean onlySave = false;
        ClassRepository repository = nameHelper.getClassRepository();
        if (repository != null) {
            for (int i=0; i < names.size(); i++) {
                String className = (String) names.get(i);
                byte[] classFile = (byte[]) classFiles.get(i);
                boolean isTopLevel = className.equals(generatedClassName);
                try {
                    if (!repository.storeClass(className, classFile,
                                               isTopLevel))
                    {
                        onlySave = true;
                    }
                } catch (IOException iox) {
                    throw WrappedException.wrapException(iox);
                }
            }

            Class[] interfaces = nameHelper.getTargetImplements();
            Class superClass = nameHelper.getTargetExtends();
            if (interfaces != null || superClass != null) {
                String adapterClassName = getScriptClassName(null, true);
                ScriptableObject obj = new NativeObject();
                int functionCount = scriptOrFn.getFunctionCount();
                for (int i = 0; i != functionCount; ++i) {
                    OptFunctionNode fn;
                    fn = (OptFunctionNode)scriptOrFn.getFunctionNode(i);
                    String name = fn.getFunctionName();
                    if (name != null && name.length() != 0) {
                        obj.put(fn.getFunctionName(), obj, fn);
                    }
                }
                if (superClass == null) {
                    superClass = ScriptRuntime.ObjectClass;
                }
                byte[] classFile = JavaAdapter.createAdapterCode(
                                       cx, obj, adapterClassName,
                                       superClass, interfaces,
                                       generatedClassName);
                try {
                    if (!repository.storeClass(adapterClassName, classFile,
                                               true))
                    {
                        onlySave = true;
                    }
                } catch (IOException iox) {
                    throw WrappedException.wrapException(iox);
                }
            }
        }

        if (onlySave) { return null; }

        Exception e = null;
        Class result = null;
        ClassLoader parentLoader = cx.getApplicationClassLoader();
        GeneratedClassLoader loader;
        if (securityController == null) {
            loader = cx.createClassLoader(parentLoader);
        } else {
            loader = securityController.createClassLoader(parentLoader,
                                                          securityDomain);
        }

        try {
            for (int i=0; i < names.size(); i++) {
                String className = (String) names.get(i);
                byte[] classFile = (byte[]) classFiles.get(i);
                boolean isTopLevel = className.equals(generatedClassName);
                try {
                    Class cl = loader.defineClass(className, classFile);
                    if (isTopLevel) {
                        result = cl;
                    }
                } catch (ClassFormatError ex) {
                    throw new RuntimeException(ex.toString());
                }
            }
            loader.linkClass(result);
        } catch (SecurityException x) {
            e = x;
        } catch (IllegalArgumentException x) {
            e = x;
        }
        if (e != null)
            throw new RuntimeException("Malformed optimizer package " + e);

        if (inFunction) {
            NativeFunction f;
            try {
                Constructor ctor = result.getConstructors()[0];
                Object[] initArgs = { scope, cx };
                f = (NativeFunction)ctor.newInstance(initArgs);
            } catch (Exception ex) {
                throw new RuntimeException
                    ("Unable to instantiate compiled class:"+ex.toString());
            }
            OptRuntime.initFunction(f, fnCurrent.getFunctionType(), scope, cx);
            return f;
        } else {
            NativeScript script;
            try {
                script = (NativeScript) result.newInstance();
            } catch (Exception ex) {
                throw new RuntimeException
                    ("Unable to instantiate compiled class:"+ex.toString());
            }
            if (scope != null) {
                script.setPrototype(script.getClassPrototype(scope, "Script"));
                script.setParentScope(scope);
            }
            return script;
        }
    }

    private String getScriptClassName(String functionName, boolean primary)
    {
        String result = nameHelper.getScriptClassName(functionName, primary);

        // We wish to produce unique class names between calls to reset()
        // we disregard case since we may write the class names to file
        // systems that are case insensitive
        String lowerResult = result.toLowerCase();
        String base = lowerResult;
        int count = 0;
        while (classNames.has(lowerResult)) {
            lowerResult = base + ++count;
        }
        classNames.put(lowerResult, 0);
        return count == 0 ? result : (result + count);
    }

    private void generateCode(Context cx, ScriptOrFnNode scriptOrFn,
                              ObjArray names, ObjArray classFiles)
    {
        String superClassName;
        this.scriptOrFn = scriptOrFn;
        if (scriptOrFn.getType() == TokenStream.FUNCTION) {
            inFunction = true;
            fnCurrent = (OptFunctionNode)scriptOrFn;
            inDirectCallFunction = fnCurrent.isTargetOfDirectCall();
            generatedClassName = fnCurrent.getClassName();
            superClassName = FUNCTION_SUPER_CLASS_NAME;
        } else {
            // better be a script
            if (scriptOrFn.getType() != TokenStream.SCRIPT) badTree();
            inFunction = false;
            boolean isPrimary = (nameHelper.getTargetExtends() == null
                                 && nameHelper.getTargetImplements() == null);
            generatedClassName = getScriptClassName(null, isPrimary);
            superClassName = SCRIPT_SUPER_CLASS_NAME;
        }
        generatedClassSignature = classNameToSignature(generatedClassName);

        itsUseDynamicScope = cx.hasCompileFunctionsWithDynamicScope();

        itsSourceFile = null;
        // default is to generate debug info
        if (!cx.isGeneratingDebugChanged() || cx.isGeneratingDebug()) {
            itsSourceFile = scriptOrFn.getSourceName();
        }
        version = cx.getLanguageVersion();
        optLevel = cx.getOptimizationLevel();

        // Generate nested function code
        int functionCount = scriptOrFn.getFunctionCount();
        for (int i = 0; i != functionCount; ++i) {
            OptFunctionNode fn = (OptFunctionNode)scriptOrFn.getFunctionNode(i);
            Codegen codegen = new Codegen(mainCodegen);
            codegen.generateCode(cx, fn, names, classFiles);
        }

        classFile = new ClassFileWriter(generatedClassName, superClassName,
                                        itsSourceFile);

        Node codegenBase;
        if (inFunction) {
            generateInit(cx, superClassName);
            if (inDirectCallFunction) {
                classFile.startMethod("call",
                                      "(Lorg/mozilla/javascript/Context;" +
                                      "Lorg/mozilla/javascript/Scriptable;" +
                                      "Lorg/mozilla/javascript/Scriptable;" +
                                      "[Ljava/lang/Object;)Ljava/lang/Object;",
                                      (short)(ClassFileWriter.ACC_PUBLIC
                                              | ClassFileWriter.ACC_FINAL));
                addByteCode(ByteCode.ALOAD_0);
                addByteCode(ByteCode.ALOAD_1);
                addByteCode(ByteCode.ALOAD_2);
                addByteCode(ByteCode.ALOAD_3);
                for (int i = 0; i < scriptOrFn.getParamCount(); i++) {
                    push(i);
                    addByteCode(ByteCode.ALOAD, 4);
                    addByteCode(ByteCode.ARRAYLENGTH);
                    int undefArg = acquireLabel();
                    int beyond = acquireLabel();
                    addByteCode(ByteCode.IF_ICMPGE, undefArg);
                    addByteCode(ByteCode.ALOAD, 4);
                    push(i);
                    addByteCode(ByteCode.AALOAD);
                    push(0.0);
                    addByteCode(ByteCode.GOTO, beyond);
                    markLabel(undefArg);
                    pushUndefined();
                    push(0.0);
                    markLabel(beyond);
                }
                addByteCode(ByteCode.ALOAD, 4);
                addVirtualInvoke(generatedClassName,
                                "callDirect",
                                fnCurrent.getDirectCallMethodSignature());
                addByteCode(ByteCode.ARETURN);
                classFile.stopMethod((short)5, null);
                // 1 for this, 1 for js this, 1 for args[]

                emitDirectConstructor();

                startCodeBodyMethod("callDirect",
                                    fnCurrent.getDirectCallMethodSignature());
                assignParameterJRegs(fnCurrent);
                if (!fnCurrent.getParameterNumberContext()) {
                    // make sure that all parameters are objects
                    itsForcedObjectParameters = true;
                    for (int i = 0; i < fnCurrent.getParamCount(); i++) {
                        OptLocalVariable lVar = fnCurrent.getVar(i);
                        aload(lVar.getJRegister());
                        classFile.add(ByteCode.GETSTATIC,
                                      "java/lang/Void",
                                      "TYPE",
                                      "Ljava/lang/Class;");
                        int isObjectLabel = acquireLabel();
                        addByteCode(ByteCode.IF_ACMPNE, isObjectLabel);
                        addByteCode(ByteCode.NEW,"java/lang/Double");
                        addByteCode(ByteCode.DUP);
                        dload((short)(lVar.getJRegister() + 1));
                        addDoubleConstructor();
                        astore(lVar.getJRegister());
                        markLabel(isObjectLabel);
                    }
                }
                generatePrologue(cx, scriptOrFn.getParamCount());
            } else {
                startCodeBodyMethod("call",
                                    "(Lorg/mozilla/javascript/Context;"
                                    +"Lorg/mozilla/javascript/Scriptable;"
                                    +"Lorg/mozilla/javascript/Scriptable;"
                                    +"[Ljava/lang/Object;)Ljava/lang/Object;");
                generatePrologue(cx, -1);
            }
            codegenBase = scriptOrFn.getLastChild();
        } else {
            // script
            classFile.addInterface("org/mozilla/javascript/Script");
            generateInit(cx, superClassName);
            generateScriptCtor(cx, superClassName);
            generateMain(cx);
            generateExecute(cx);
            startCodeBodyMethod("call",
                                "(Lorg/mozilla/javascript/Context;"
                                +"Lorg/mozilla/javascript/Scriptable;"
                                +"Lorg/mozilla/javascript/Scriptable;"
                                +"[Ljava/lang/Object;)Ljava/lang/Object;");
            generatePrologue(cx, -1);
            int linenum = scriptOrFn.getEndLineno();
            if (linenum != -1)
              classFile.addLineNumberEntry((short)linenum);
            scriptOrFn.addChildToBack(new Node(TokenStream.RETURN));
            codegenBase = scriptOrFn;
        }

        generateCodeFromNode(codegenBase, null, -1, -1);

        generateEpilogue();

        finishMethod(cx, debugVars);

        emitConstantDudeInitializers();

        if (isMainCodegen && mainCodegen.directCallTargets != null) {
            int N = mainCodegen.directCallTargets.size();
            for (int i = 0; i != N; ++i) {
                OptFunctionNode fn = (OptFunctionNode)directCallTargets.get(i);
                classFile.addField(getDirectTargetFieldName(i),
                                   classNameToSignature(fn.getClassName()),
                                   (short)0);
            }
        }

        byte[] bytes = classFile.toByteArray();

        names.add(generatedClassName);
        classFiles.add(bytes);

        classFile = null;
    }

    private void emitDirectConstructor()
    {
/*
    we generate ..
        Scriptable directConstruct(<directCallArgs>) {
            Scriptable newInstance = createObject(cx, scope);
            Object val = callDirect(cx, scope, newInstance, <directCallArgs>);
            if (val instanceof Scriptable && val != Undefined.instance) {
                return (Scriptable) val;
            }
            return newInstance;
        }
*/
        short flags = (short)(ClassFileWriter.ACC_PUBLIC
                            | ClassFileWriter.ACC_FINAL);
        classFile.startMethod("constructDirect",
                              fnCurrent.getDirectCallMethodSignature(),
                              flags);

        int argCount = fnCurrent.getParamCount();
        int firstLocal = (4 + argCount * 3) + 1;

        aload((short)0); // this
        aload((short)1); // cx
        aload((short)2); // scope
        addVirtualInvoke("org/mozilla/javascript/BaseFunction",
                         "createObject",
                         "(Lorg/mozilla/javascript/Context;"
                         +"Lorg/mozilla/javascript/Scriptable;"
                         +")Lorg/mozilla/javascript/Scriptable;");
        astore((short)firstLocal);

        aload((short)0);
        aload((short)1);
        aload((short)2);
        aload((short)firstLocal);
        for (int i = 0; i < argCount; i++) {
            aload((short)(4 + (i * 3)));
            dload((short)(5 + (i * 3)));
        }
        aload((short)(4 + argCount * 3));
        addVirtualInvoke(generatedClassName,
                         "callDirect",
                         fnCurrent.getDirectCallMethodSignature());
        int exitLabel = acquireLabel();
        addByteCode(ByteCode.DUP); // make a copy of callDirect result
        addByteCode(ByteCode.INSTANCEOF, "org/mozilla/javascript/Scriptable");
        addByteCode(ByteCode.IFEQ, exitLabel);
        addByteCode(ByteCode.DUP); // make a copy of callDirect result
        pushUndefined();
        addByteCode(ByteCode.IF_ACMPEQ, exitLabel);
        // cast callDirect result
        addByteCode(ByteCode.CHECKCAST, "org/mozilla/javascript/Scriptable");
        addByteCode(ByteCode.ARETURN);
        markLabel(exitLabel);

        aload((short)firstLocal);
        addByteCode(ByteCode.ARETURN);

        classFile.stopMethod((short)(firstLocal + 1), null);

    }

    private static void assignParameterJRegs(OptFunctionNode fnCurrent) {
        // 0 is reserved for function Object 'this'
        // 1 is reserved for context
        // 2 is reserved for parentScope
        // 3 is reserved for script 'this'
        short jReg = 4;
        int parameterCount = fnCurrent.getParamCount();
        for (int i = 0; i < parameterCount; i++) {
            OptLocalVariable lVar = fnCurrent.getVar(i);
            lVar.assignJRegister(jReg);
            jReg += 3;  // 3 is 1 for Object parm and 2 for double parm
        }
    }

    private void generateMain(Context cx)
    {
        classFile.startMethod("main", "([Ljava/lang/String;)V",
                              (short)(ClassFileWriter.ACC_PUBLIC
                                      | ClassFileWriter.ACC_STATIC));
        push(generatedClassName);  // load the name of this class
        classFile.addInvoke(ByteCode.INVOKESTATIC,
                            "java/lang/Class",
                            "forName",
                            "(Ljava/lang/String;)Ljava/lang/Class;");
        addByteCode(ByteCode.ALOAD_0); // load 'args'
        addScriptRuntimeInvoke("main",
                              "(Ljava/lang/Class;[Ljava/lang/String;)V");
        addByteCode(ByteCode.RETURN);
        // 3 = String[] args
        classFile.stopMethod((short)1, null);
    }

    private void generateExecute(Context cx)
    {
        classFile.startMethod("exec",
                              "(Lorg/mozilla/javascript/Context;"
                              +"Lorg/mozilla/javascript/Scriptable;"
                              +")Ljava/lang/Object;",
                              (short)(ClassFileWriter.ACC_PUBLIC
                                      | ClassFileWriter.ACC_FINAL));

        final byte ALOAD_CONTEXT = ByteCode.ALOAD_1;
        final byte ALOAD_SCOPE = ByteCode.ALOAD_2;

        String slashName = generatedClassName.replace('.', '/');

        // to begin a script, call the initScript method
        addByteCode(ByteCode.ALOAD_0); // load 'this'
        addByteCode(ALOAD_SCOPE);
        addByteCode(ALOAD_CONTEXT);
        addVirtualInvoke(slashName,
                         "initScript",
                         "(Lorg/mozilla/javascript/Scriptable;"
                         +"Lorg/mozilla/javascript/Context;"
                         +")V");

        addByteCode(ByteCode.ALOAD_0); // load 'this'
        addByteCode(ALOAD_CONTEXT);
        addByteCode(ALOAD_SCOPE);
        addByteCode(ByteCode.DUP);
        addByteCode(ByteCode.ACONST_NULL);
        addVirtualInvoke(slashName,
                         "call",
                         "(Lorg/mozilla/javascript/Context;"
                         +"Lorg/mozilla/javascript/Scriptable;"
                         +"Lorg/mozilla/javascript/Scriptable;"
                         +"[Ljava/lang/Object;"
                         +")Ljava/lang/Object;");

        addByteCode(ByteCode.ARETURN);
        // 3 = this + context + scope
        classFile.stopMethod((short)3, null);
    }

    private void generateScriptCtor(Context cx, String superClassName)
    {
        classFile.startMethod("<init>", "()V", ClassFileWriter.ACC_PUBLIC);
        addByteCode(ByteCode.ALOAD_0);
        addSpecialInvoke(superClassName, "<init>", "()V");
        addByteCode(ByteCode.RETURN);
        // 1 parameter = this
        classFile.stopMethod((short)1, null);
    }

    private void generateInit(Context cx, String superClassName)
    {
        String methodName = (inFunction) ? "<init>" :  "initScript";
        classFile.startMethod(methodName,
                              "(Lorg/mozilla/javascript/Scriptable;"
                              +"Lorg/mozilla/javascript/Context;)V",
                              ClassFileWriter.ACC_PUBLIC);

        final byte ALOAD_SCOPE = ByteCode.ALOAD_1;
        final byte ALOAD_CONTEXT = ByteCode.ALOAD_2;

        if (inFunction) {
            addByteCode(ByteCode.ALOAD_0);
            addSpecialInvoke(superClassName, "<init>", "()V");

            addByteCode(ByteCode.ALOAD_0);
            addByteCode(ALOAD_SCOPE);
            classFile.add(ByteCode.PUTFIELD,
                          "org/mozilla/javascript/ScriptableObject",
                          "parent", "Lorg/mozilla/javascript/Scriptable;");

            /*
             * Generate code to initialize functionName field with the name
             * of the function.
             */
            String name = fnCurrent.getFunctionName();
            if (name.length() != 0) {
                addByteCode(ByteCode.ALOAD_0);
                classFile.addLoadConstant(name);
                classFile.add(ByteCode.PUTFIELD,
                              "org/mozilla/javascript/NativeFunction",
                              "functionName", "Ljava/lang/String;");
            }
        }

        /*
         * Generate code to initialize argNames string array with the names
         * of the parameters and the vars. Initialize argCount
         * to the number of formal parameters.
         */
        int N = scriptOrFn.getParamAndVarCount();
        if (N != 0) {
            push(N);
            addByteCode(ByteCode.ANEWARRAY, "java/lang/String");
            for (int i = 0; i != N; i++) {
                addByteCode(ByteCode.DUP);
                push(i);
                push(scriptOrFn.getParamOrVarName(i));
                addByteCode(ByteCode.AASTORE);
            }
            addByteCode(ByteCode.ALOAD_0);
            addByteCode(ByteCode.SWAP);
            classFile.add(ByteCode.PUTFIELD,
                          "org/mozilla/javascript/NativeFunction",
                          "argNames", "[Ljava/lang/String;");
        }

        int parmCount = scriptOrFn.getParamCount();
        if (parmCount != 0) {
            if (!inFunction) Context.codeBug();
            addByteCode(ByteCode.ALOAD_0);
            push(parmCount);
            classFile.add(ByteCode.PUTFIELD,
                    "org/mozilla/javascript/NativeFunction",
                    "argCount", "S");
        }

        // Initialize NativeFunction.version with Context's version.
        if (cx.getLanguageVersion() != 0) {
            addByteCode(ByteCode.ALOAD_0);
            push(cx.getLanguageVersion());
            classFile.add(ByteCode.PUTFIELD,
                    "org/mozilla/javascript/NativeFunction",
                    "version", "S");
        }

        // precompile all regexp literals
        int regexpCount = scriptOrFn.getRegexpCount();
        if (regexpCount != 0) {
            for (int i = 0; i != regexpCount; ++i) {
                String fieldName = getRegexpFieldName(i);
                short flags = ClassFileWriter.ACC_PRIVATE;
                if (inFunction) { flags |= ClassFileWriter.ACC_FINAL; }
                classFile.addField(
                    fieldName,
                    "Lorg/mozilla/javascript/regexp/NativeRegExp;",
                    flags);
                addByteCode(ByteCode.ALOAD_0);    // load 'this'

                addByteCode(ByteCode.NEW,
                            "org/mozilla/javascript/regexp/NativeRegExp");
                addByteCode(ByteCode.DUP);

                addByteCode(ALOAD_CONTEXT);
                addByteCode(ALOAD_SCOPE);
                push(scriptOrFn.getRegexpString(i));
                String regexpFlags = scriptOrFn.getRegexpFlags(i);
                if (regexpFlags == null) {
                    addByteCode(ByteCode.ACONST_NULL);
                } else {
                    push(regexpFlags);
                }
                push(0);

                addSpecialInvoke("org/mozilla/javascript/regexp/NativeRegExp",
                                 "<init>",
                                 "(Lorg/mozilla/javascript/Context;"
                                 +"Lorg/mozilla/javascript/Scriptable;"
                                 +"Ljava/lang/String;Ljava/lang/String;"
                                 +"Z"
                                 +")V");
                classFile.add(ByteCode.PUTFIELD, generatedClassName,
                              fieldName,
                              "Lorg/mozilla/javascript/regexp/NativeRegExp;");
            }
        }

        classFile.addField(MAIN_SCRIPT_FIELD,
                           mainCodegen.generatedClassSignature,
                           (short)0);
        // For top level script or function init scriptMaster to self
        if (isMainCodegen) {
            addByteCode(ByteCode.ALOAD_0);
            addByteCode(ByteCode.DUP);
            classFile.add(ByteCode.PUTFIELD, generatedClassName,
                          MAIN_SCRIPT_FIELD,
                          mainCodegen.generatedClassSignature);
        }

        addByteCode(ByteCode.RETURN);
        // 3 = this + scope + context
        classFile.stopMethod((short)3, null);

        // Add static method to return encoded source tree for decompilation
        // which will be called from OptFunction/OptScrript.getSourcesTree
        // via reflection. See NativeFunction.getSourcesTree for documentation.
        // Note that nested function decompilation currently depends on the
        // elements of the fns array being defined in source order.
        // (per function/script, starting from 0.)
        // Change Parser if changing ordering.

        if (cx.isGeneratingSource()) {
            String source = scriptOrFn.getEncodedSource();
            if (source != null && source.length() < 65536) {
                short flags = ClassFileWriter.ACC_PUBLIC
                            | ClassFileWriter.ACC_STATIC;
                String getSourceMethodStr = "getSourcesTreeImpl";
                classFile.startMethod(getSourceMethodStr,
                                      "()Ljava/lang/Object;",
                                      (short)flags);
                int functionCount = scriptOrFn.getFunctionCount();
                if (functionCount == 0) {
                    // generate return <source-literal-string>;
                    push(source);
                } else {
                    // generate
                    // Object[] result = new Object[1 + functionCount];
                    // result[0] = <source-literal-string>
                    // result[1] = Class1.getSourcesTreeImpl();
                    // ...
                    // result[functionCount] = ClassN.getSourcesTreeImpl();
                    // return result;
                    push(1 + functionCount);
                    addByteCode(ByteCode.ANEWARRAY, "java/lang/Object");
                       addByteCode(ByteCode.DUP); // dup array reference
                    push(0);
                    push(source);
                    addByteCode(ByteCode.AASTORE);
                    for (int i = 0; i != functionCount; ++i) {
                        OptFunctionNode fn;
                        addByteCode(ByteCode.DUP); // dup array reference
                        push(1 + i);
                        fn = (OptFunctionNode)scriptOrFn.getFunctionNode(i);
                        classFile.addInvoke(ByteCode.INVOKESTATIC,
                                            fn.getClassName(),
                                            getSourceMethodStr,
                                            "()Ljava/lang/Object;");
                        addByteCode(ByteCode.AASTORE);
                    }
                }
                addByteCode(ByteCode.ARETURN);
                // 0: no this and no argument
                classFile.stopMethod((short)0, null);
            }
        }
    }

    /**
     * Generate the prologue for a function or script.
     *
     * @param cx the context
     * @param inFunction true if generating the prologue for a function
     *        (as opposed to a script)
     * @param directParameterCount number of parameters for direct call,
     *        or -1 if not direct call
     */
    private void generatePrologue(Context cx, int directParameterCount)
    {
        if (inFunction && !itsUseDynamicScope &&
            directParameterCount == -1)
        {
            // Unless we're either using dynamic scope or we're in a
            // direct call, use the enclosing scope of the function as our
            // variable object.
            aload(funObjLocal);
            classFile.addInvoke(ByteCode.INVOKEINTERFACE,
                                "org/mozilla/javascript/Scriptable",
                                "getParentScope",
                                "()Lorg/mozilla/javascript/Scriptable;");
            astore(variableObjectLocal);
        }

        if (directParameterCount > 0) {
            for (int i = 0; i < (3 * directParameterCount); i++)
                reserveWordLocal(i + 4);               // reserve 'args'
        }
        // reserve 'args[]'
        argsLocal = reserveWordLocal(directParameterCount <= 0
                                     ? 4 : (3 * directParameterCount) + 4);

        // These locals are to be pre-allocated since they need function scope.
        // They are primarily used by the exception handling mechanism
        int localCount = scriptOrFn.getLocalCount();
        if (localCount != 0) {
            itsLocalAllocationBase = (short)(argsLocal + 1);
            for (int i = 0; i < localCount; i++) {
                reserveWordLocal(itsLocalAllocationBase + i);
            }
        }

        if (inFunction && fnCurrent.getCheckThis()) {
            // Nested functions must check their 'this' value to
            //  insure it is not an activation object:
            //  see 10.1.6 Activation Object
            aload(thisObjLocal);
            addScriptRuntimeInvoke("getThis",
                                   "(Lorg/mozilla/javascript/Scriptable;"
                                   +")Lorg/mozilla/javascript/Scriptable;");
            astore(thisObjLocal);
        }

        hasVarsInRegs = inFunction && !fnCurrent.requiresActivation();
        if (hasVarsInRegs) {
            // No need to create activation. Pad arguments if need be.
            int parmCount = scriptOrFn.getParamCount();
            if (inFunction && parmCount > 0 && directParameterCount < 0) {
                // Set up args array
                // check length of arguments, pad if need be
                aload(argsLocal);
                addByteCode(ByteCode.ARRAYLENGTH);
                push(parmCount);
                int label = acquireLabel();
                addByteCode(ByteCode.IF_ICMPGE, label);
                aload(argsLocal);
                push(parmCount);
                addScriptRuntimeInvoke("padArguments",
                                       "([Ljava/lang/Object;I"
                                       +")[Ljava/lang/Object;");
                astore(argsLocal);
                markLabel(label);
            }

            // REMIND - only need to initialize the vars that don't get a value
            // before the next call and are used in the function
            short firstUndefVar = -1;
            for (int i = 0; i < fnCurrent.getVarCount(); i++) {
                OptLocalVariable lVar = fnCurrent.getVar(i);
                if (lVar.isNumber()) {
                    lVar.assignJRegister(getNewWordPairLocal());
                    push(0.0);
                    dstore(lVar.getJRegister());
                } else if (lVar.isParameter()) {
                    if (directParameterCount < 0) {
                        lVar.assignJRegister(getNewWordLocal());
                        aload(argsLocal);
                        push(i);
                        addByteCode(ByteCode.AALOAD);
                        astore(lVar.getJRegister());
                    }
                } else {
                    lVar.assignJRegister(getNewWordLocal());
                    if (firstUndefVar == -1) {
                        pushUndefined();
                        firstUndefVar = lVar.getJRegister();
                    } else {
                        aload(firstUndefVar);
                    }
                    astore(lVar.getJRegister());
                }
                lVar.setStartPC(classFile.getCurrentCodeOffset());
            }

            // Indicate that we should generate debug information for
            // the variable table. (If we're generating debug info at
            // all.)
            debugVars = fnCurrent.getVarsArray();

            // Skip creating activation object.
            return;
        }

        if (directParameterCount > 0) {
            // We're going to create an activation object, so we
            // need to get an args array with all the arguments in it.

            aload(argsLocal);
            push(directParameterCount);
            addOptRuntimeInvoke("padStart",
                                "([Ljava/lang/Object;I)[Ljava/lang/Object;");
            astore(argsLocal);
            for (int i=0; i < directParameterCount; i++) {
                aload(argsLocal);
                push(i);
                // "3" is 1 for Object parm and 2 for double parm, and
                // "4" is to account for the context, etc. parms
                aload((short) (3*i+4));
                addByteCode(ByteCode.AASTORE);
            }
        }

        String debugVariableName;
        if (inFunction) {
            aload(contextLocal);
            aload(variableObjectLocal);
            aload(funObjLocal);
            aload(thisObjLocal);
            aload(argsLocal);
            addScriptRuntimeInvoke("initVarObj",
                                   "(Lorg/mozilla/javascript/Context;"
                                   +"Lorg/mozilla/javascript/Scriptable;"
                                   +"Lorg/mozilla/javascript/NativeFunction;"
                                   +"Lorg/mozilla/javascript/Scriptable;"
                                   +"[Ljava/lang/Object;"
                                   +")Lorg/mozilla/javascript/Scriptable;");
            debugVariableName = "activation";
        } else {
            aload(contextLocal);
            aload(variableObjectLocal);
            aload(funObjLocal);
            aload(thisObjLocal);
            push(0);
            addScriptRuntimeInvoke("initScript",
                                   "(Lorg/mozilla/javascript/Context;"
                                   +"Lorg/mozilla/javascript/Scriptable;"
                                   +"Lorg/mozilla/javascript/NativeFunction;"
                                   +"Lorg/mozilla/javascript/Scriptable;"
                                   +"Z"
                                   +")Lorg/mozilla/javascript/Scriptable;");
            debugVariableName = "global";
        }
        astore(variableObjectLocal);

        int functionCount = scriptOrFn.getFunctionCount();
        for (int i = 0; i != functionCount; i++) {
            OptFunctionNode fn = (OptFunctionNode)scriptOrFn.getFunctionNode(i);
            if (fn.getFunctionType() == FunctionNode.FUNCTION_STATEMENT) {
                visitFunction(fn, FunctionNode.FUNCTION_STATEMENT);
            }
        }

        // default is to generate debug info
        if (!cx.isGeneratingDebugChanged() || cx.isGeneratingDebug()) {
            OptLocalVariable lv = new OptLocalVariable(debugVariableName,
                                                       false);
            lv.assignJRegister(variableObjectLocal);
            lv.setStartPC(classFile.getCurrentCodeOffset());

            debugVars = new OptLocalVariable[1];
            debugVars[0] = lv;
        }

        if (!inFunction) {
            // OPT: use dataflow to prove that this assignment is dead
            scriptResultLocal = getNewWordLocal();
            pushUndefined();
            astore(scriptResultLocal);
        }

        if (inFunction) {
            if (fnCurrent.itsContainsCalls0) {
                itsZeroArgArray = getNewWordLocal();
                classFile.add(ByteCode.GETSTATIC,
                        "org/mozilla/javascript/ScriptRuntime",
                        "emptyArgs", "[Ljava/lang/Object;");
                astore(itsZeroArgArray);
            }
            if (fnCurrent.itsContainsCalls1) {
                itsOneArgArray = getNewWordLocal();
                push(1);
                addByteCode(ByteCode.ANEWARRAY, "java/lang/Object");
                astore(itsOneArgArray);
            }
        }
    }

    private void generateEpilogue() {
        if (epilogueLabel != -1) {
            classFile.markLabel(epilogueLabel);
        }
        if (!hasVarsInRegs || !inFunction) {
            // restore caller's activation
            aload(contextLocal);
            addScriptRuntimeInvoke("popActivation",
                                   "(Lorg/mozilla/javascript/Context;)V");
        }
        addByteCode(ByteCode.ARETURN);
    }

    private void emitConstantDudeInitializers() {
        int N = itsConstantListSize;
        if (N == 0)
            return;

        classFile.startMethod("<clinit>", "()V",
            (short)(ClassFileWriter.ACC_STATIC + ClassFileWriter.ACC_FINAL));

        double[] array = itsConstantList;
        for (int i = 0; i != N; ++i) {
            double num = array[i];
            String constantName = "jsK_" + i;
            String constantType = getStaticConstantWrapperType(num);
            classFile.addField(constantName, constantType,
                               ClassFileWriter.ACC_STATIC);
            pushAsWrapperObject(num);
            classFile.add(ByteCode.PUTSTATIC,
                          classFile.fullyQualifiedForm(generatedClassName),
                          constantName, constantType);
        }

        addByteCode(ByteCode.RETURN);
        classFile.stopMethod((short)0, null);
    }

    private void generateCodeFromNode(Node node, Node parent, int trueLabel,
                                      int falseLabel)
    {
        // System.out.println("gen code for " + node.toString());

        int type = node.getType();
        Node child = node.getFirstChild();
        switch (type) {
              case TokenStream.LOOP:
              case TokenStream.WITH:
              case TokenStream.LABEL:
                visitStatement(node);
                while (child != null) {
                    generateCodeFromNode(child, node, trueLabel, falseLabel);
                    child = child.getNext();
                }
                break;

              case TokenStream.CASE:
              case TokenStream.DEFAULT:
                // XXX shouldn't these be StatementNodes?

              case TokenStream.SCRIPT:
              case TokenStream.BLOCK:
              case TokenStream.VOID:
              case TokenStream.NOP:
                // no-ops.
                visitStatement(node);
                while (child != null) {
                    generateCodeFromNode(child, node, trueLabel, falseLabel);
                    child = child.getNext();
                }
                break;

              case TokenStream.FUNCTION:
                if (inFunction || parent.getType() != TokenStream.SCRIPT) {
                    int fnIndex = node.getExistingIntProp(Node.FUNCTION_PROP);
                    OptFunctionNode fn;
                    fn = (OptFunctionNode)scriptOrFn.getFunctionNode(fnIndex);
                    int t = fn.getFunctionType();
                    if (t != FunctionNode.FUNCTION_STATEMENT) {
                        visitFunction(fn, t);
                    }
                }
                break;

              case TokenStream.NAME:
                visitName(node);
                break;

              case TokenStream.NEW:
              case TokenStream.CALL:
                visitCall(node, type, child);
                break;

              case TokenStream.NUMBER:
              case TokenStream.STRING:
                visitLiteral(node);
                break;

              case TokenStream.PRIMARY:
                visitPrimary(node);
                break;

              case TokenStream.REGEXP:
                visitObject(node);
                break;

              case TokenStream.TRY:
                visitTryCatchFinally(node, child);
                break;

              case TokenStream.THROW:
                visitThrow(node, child);
                break;

              case TokenStream.RETURN:
                visitReturn(node, child);
                break;

              case TokenStream.SWITCH:
                visitSwitch(node, child);
                break;

              case TokenStream.COMMA: {
                Node next = child.getNext();
                while (next != null) {
                    generateCodeFromNode(child, node, -1, -1);
                    addByteCode(ByteCode.POP);
                    child = next;
                    next = next.getNext();
                }
                generateCodeFromNode(child, node, trueLabel, falseLabel);
                break;
              }

              case TokenStream.NEWSCOPE:
                addScriptRuntimeInvoke("newScope",
                                       "()Lorg/mozilla/javascript/Scriptable;");
                break;

              case TokenStream.ENTERWITH:
                visitEnterWith(node, child);
                break;

              case TokenStream.LEAVEWITH:
                visitLeaveWith(node, child);
                break;

              case TokenStream.ENUMINIT:
                visitEnumInit(node, child);
                break;

              case TokenStream.ENUMNEXT:
                visitEnumNext(node, child);
                break;

              case TokenStream.ENUMDONE:
                visitEnumDone(node, child);
                break;

              case TokenStream.POP:
                visitStatement(node);
                if (child.getType() == TokenStream.SETVAR) {
                    /* special case this so as to avoid unnecessary
                    load's & pop's */
                    visitSetVar(child, child.getFirstChild(), false);
                }
                else {
                    while (child != null) {
                        generateCodeFromNode(child, node, trueLabel, falseLabel);
                        child = child.getNext();
                    }
                    if (node.getIntProp(Node.ISNUMBER_PROP, -1) != -1)
                        addByteCode(ByteCode.POP2);
                    else
                        addByteCode(ByteCode.POP);
                }
                break;

              case TokenStream.POPV:
                visitStatement(node);
                while (child != null) {
                    generateCodeFromNode(child, node, trueLabel, falseLabel);
                    child = child.getNext();
                }
                astore(scriptResultLocal);
                break;

              case TokenStream.TARGET:
                visitTarget(node);
                break;

              case TokenStream.JSR:
              case TokenStream.GOTO:
              case TokenStream.IFEQ:
              case TokenStream.IFNE:
                visitGOTO(node, type, child);
                break;

              case TokenStream.UNARYOP:
                visitUnary(node, child, trueLabel, falseLabel);
                break;

              case TokenStream.TYPEOF:
                visitTypeof(node, child);
                break;

              case TokenStream.INC:
                visitIncDec(node, true);
                break;

              case TokenStream.DEC:
                visitIncDec(node, false);
                break;

              case TokenStream.OR:
              case TokenStream.AND: {
                    if (trueLabel == -1) {
                        generateCodeFromNode(child, node, trueLabel, falseLabel);
                        addByteCode(ByteCode.DUP);
                        addScriptRuntimeInvoke("toBoolean",
                                               "(Ljava/lang/Object;)Z");
                        int falseTarget = acquireLabel();
                        if (type == TokenStream.AND)
                            addByteCode(ByteCode.IFEQ, falseTarget);
                        else
                            addByteCode(ByteCode.IFNE, falseTarget);
                        addByteCode(ByteCode.POP);
                        generateCodeFromNode(child.getNext(), node, trueLabel, falseLabel);
                        markLabel(falseTarget);
                    }
                    else {
                        int interLabel = acquireLabel();
                        if (type == TokenStream.AND) {
                            generateCodeFromNode(child, node, interLabel,
                                                 falseLabel);
                            if (!childIsBoolean(child)) {
                                addScriptRuntimeInvoke("toBoolean",
                                                       "(Ljava/lang/Object;)Z");
                                addByteCode(ByteCode.IFNE, interLabel);
                                addByteCode(ByteCode.GOTO, falseLabel);
                            }
                        }
                        else {
                            generateCodeFromNode(child, node, trueLabel, interLabel);
                            if (!childIsBoolean(child)) {
                                addScriptRuntimeInvoke("toBoolean",
                                                       "(Ljava/lang/Object;)Z");
                                addByteCode(ByteCode.IFNE, trueLabel);
                                addByteCode(ByteCode.GOTO, interLabel);
                            }
                        }
                        markLabel(interLabel);
                        child = child.getNext();
                        generateCodeFromNode(child, node, trueLabel, falseLabel);
                        if (!childIsBoolean(child)) {
                            addScriptRuntimeInvoke("toBoolean",
                                                   "(Ljava/lang/Object;)Z");
                            addByteCode(ByteCode.IFNE, trueLabel);
                            addByteCode(ByteCode.GOTO, falseLabel);
                        }
                    }
                }
                break;

              case TokenStream.ADD: {
                    generateCodeFromNode(child, node, -1, -1);
                    generateCodeFromNode(child.getNext(), node, -1, -1);
                    switch (node.getIntProp(Node.ISNUMBER_PROP, -1)) {
                        case Node.BOTH:
                            addByteCode(ByteCode.DADD);
                            break;
                        case Node.LEFT:
                            addOptRuntimeInvoke("add",
                                "(DLjava/lang/Object;)Ljava/lang/Object;");
                            break;
                        case Node.RIGHT:
                            addOptRuntimeInvoke("add",
                                "(Ljava/lang/Object;D)Ljava/lang/Object;");
                            break;
                        default:
                        addScriptRuntimeInvoke("add",
                                               "(Ljava/lang/Object;"
                                               +"Ljava/lang/Object;"
                                               +")Ljava/lang/Object;");
                    }
                }
                break;

              case TokenStream.MUL:
                visitArithmetic(node, ByteCode.DMUL, child, parent);
                break;

              case TokenStream.SUB:
                visitArithmetic(node, ByteCode.DSUB, child, parent);
                break;

              case TokenStream.DIV:
              case TokenStream.MOD:
                visitArithmetic(node, type == TokenStream.DIV
                                      ? ByteCode.DDIV
                                      : ByteCode.DREM, child, parent);
                break;

              case TokenStream.BITOR:
              case TokenStream.BITXOR:
              case TokenStream.BITAND:
              case TokenStream.LSH:
              case TokenStream.RSH:
              case TokenStream.URSH:
                visitBitOp(node, type, child);
                break;

              case TokenStream.CONVERT: {
                    Object toType = node.getProp(Node.TYPE_PROP);
                    if (toType == ScriptRuntime.NumberClass) {
                        addByteCode(ByteCode.NEW, "java/lang/Double");
                        addByteCode(ByteCode.DUP);
                        generateCodeFromNode(child, node,
                                             trueLabel, falseLabel);
                        addScriptRuntimeInvoke("toNumber",
                                               "(Ljava/lang/Object;)D");
                        addDoubleConstructor();
                    }
                    else {
                        if (toType == ScriptRuntime.DoubleClass) {
                                                               // cnvt to double
                                                               // (not Double)
                            generateCodeFromNode(child, node,
                                                 trueLabel, falseLabel);
                            addScriptRuntimeInvoke("toNumber",
                                                   "(Ljava/lang/Object;)D");
                        }
                        else {
                            if (toType == ScriptRuntime.ObjectClass) {
                                // convert from double
                                int prop = -1;
                                if (child.getType() == TokenStream.NUMBER) {
                                    prop = child.getIntProp(Node.ISNUMBER_PROP,
                                                            -1);
                                }
                                if (prop != -1) {
                                    child.removeProp(Node.ISNUMBER_PROP);
                                    generateCodeFromNode(child, node, trueLabel,
                                                         falseLabel);
                                    child.putIntProp(Node.ISNUMBER_PROP, prop);
                                }
                                else {
                                    addByteCode(ByteCode.NEW, "java/lang/Double");
                                    addByteCode(ByteCode.DUP);
                                    generateCodeFromNode(child, node, trueLabel, falseLabel);
                                    addDoubleConstructor();
                                }
                            }
                            else
                                badTree();
                        }
                    }
                }
                break;

              case TokenStream.RELOP:
                if (trueLabel == -1)    // need a result Object
                    visitRelOp(node, child, parent);
                else
                    visitGOTOingRelOp(node, child, parent, trueLabel, falseLabel);
                break;

              case TokenStream.EQOP:
                visitEqOp(node, child, parent, trueLabel, falseLabel);
                break;

              case TokenStream.GETPROP:
                visitGetProp(node, child);
                break;

              case TokenStream.GETELEM:
                while (child != null) {
                    generateCodeFromNode(child, node, trueLabel, falseLabel);
                    child = child.getNext();
                }
                aload(variableObjectLocal);
                if (node.getIntProp(Node.ISNUMBER_PROP, -1) != -1) {
                    addOptRuntimeInvoke(
                        "getElem",
                        "(Ljava/lang/Object;D"
                        +"Lorg/mozilla/javascript/Scriptable;"
                        +")Ljava/lang/Object;");
                }
                else {
                    addScriptRuntimeInvoke(
                        "getElem",
                        "(Ljava/lang/Object;"
                        +"Ljava/lang/Object;"
                        +"Lorg/mozilla/javascript/Scriptable;"
                        +")Ljava/lang/Object;");
                }
                break;

              case TokenStream.GETVAR: {
                OptLocalVariable lVar
                        = (OptLocalVariable)(node.getProp(Node.VARIABLE_PROP));
                visitGetVar(lVar,
                            node.getIntProp(Node.ISNUMBER_PROP, -1) != -1,
                            node.getString());
              }
              break;

              case TokenStream.SETVAR:
                visitSetVar(node, child, true);
                break;

              case TokenStream.SETNAME:
                visitSetName(node, child);
                break;

              case TokenStream.SETPROP:
                visitSetProp(node, child);
                break;

              case TokenStream.SETELEM:
                while (child != null) {
                    generateCodeFromNode(child, node, trueLabel, falseLabel);
                    child = child.getNext();
                }
                aload(variableObjectLocal);
                if (node.getIntProp(Node.ISNUMBER_PROP, -1) != -1) {
                    addOptRuntimeInvoke(
                        "setElem",
                        "(Ljava/lang/Object;"
                        +"D"
                        +"Ljava/lang/Object;"
                        +"Lorg/mozilla/javascript/Scriptable;"
                        +")Ljava/lang/Object;");
                }
                else {
                    addScriptRuntimeInvoke(
                        "setElem",
                        "(Ljava/lang/Object;"
                        +"Ljava/lang/Object;"
                        +"Ljava/lang/Object;"
                        +"Lorg/mozilla/javascript/Scriptable;"
                        +")Ljava/lang/Object;");
                }
                break;

              case TokenStream.DELPROP:
                aload(contextLocal);
                aload(variableObjectLocal);
                while (child != null) {
                    generateCodeFromNode(child, node, trueLabel, falseLabel);
                    child = child.getNext();
                }
                addScriptRuntimeInvoke("delete",
                                       "(Lorg/mozilla/javascript/Context;"
                                       +"Lorg/mozilla/javascript/Scriptable;"
                                       +"Ljava/lang/Object;"
                                       +"Ljava/lang/Object;"
                                       +")Ljava/lang/Object;");
                break;

              case TokenStream.BINDNAME:
              case TokenStream.GETBASE:
                visitBind(node, type, child);
                break;

              case TokenStream.GETTHIS:
                generateCodeFromNode(child, node, trueLabel, falseLabel);
                addScriptRuntimeInvoke("getThis",
                                       "(Lorg/mozilla/javascript/Scriptable;"
                                       +")Lorg/mozilla/javascript/Scriptable;");
                break;

              case TokenStream.PARENT:
                generateCodeFromNode(child, node, trueLabel, falseLabel);
                addScriptRuntimeInvoke("getParent",
                                       "(Ljava/lang/Object;"
                                       +")Lorg/mozilla/javascript/Scriptable;");
                break;

              case TokenStream.NEWTEMP:
                visitNewTemp(node, child);
                break;

              case TokenStream.USETEMP:
                visitUseTemp(node, child);
                break;

              case TokenStream.NEWLOCAL:
                visitNewLocal(node, child);
                break;

              case TokenStream.USELOCAL:
                visitUseLocal(node, child);
                break;

              default:
                throw new RuntimeException("Unexpected node type " +
                          TokenStream.tokenToName(type));
        }

    }

    private void visitFunction(OptFunctionNode fn, int functionType)
    {
        String fnClassName = fn.getClassName();
        addByteCode(ByteCode.NEW, fnClassName);
        // Call function constructor
        addByteCode(ByteCode.DUP);
        aload(variableObjectLocal);
        aload(contextLocal);           // load 'cx'
        addSpecialInvoke(fnClassName, "<init>",
                         "(Lorg/mozilla/javascript/Scriptable;"
                         +"Lorg/mozilla/javascript/Context;"
                         +")V");

        // Init mainScript field;
        addByteCode(ByteCode.DUP);
        addByteCode(ByteCode.ALOAD_0);
        classFile.add(ByteCode.GETFIELD, generatedClassName,
                      MAIN_SCRIPT_FIELD,
                      mainCodegen.generatedClassSignature);
        classFile.add(ByteCode.PUTFIELD, fnClassName,
                      MAIN_SCRIPT_FIELD,
                      mainCodegen.generatedClassSignature);

        int directTargetIndex = fn.getDirectTargetIndex();
        if (directTargetIndex >= 0) {
            addByteCode(ByteCode.DUP);
            addByteCode(ByteCode.ALOAD_0);
            classFile.add(ByteCode.GETFIELD, generatedClassName,
                          MAIN_SCRIPT_FIELD,
                          mainCodegen.generatedClassSignature);
            addByteCode(ByteCode.SWAP);
            classFile.add(ByteCode.PUTFIELD, mainCodegen.generatedClassName,
                          getDirectTargetFieldName(directTargetIndex),
                          classNameToSignature(fn.getClassName()));
        }

        // Dup function reference for function expressions to have it
        // on top of the stack when initFunction returns
        if (functionType != FunctionNode.FUNCTION_STATEMENT) {
            addByteCode(ByteCode.DUP);
        }
        push(functionType);
        aload(variableObjectLocal);
        aload(contextLocal);           // load 'cx'
        addOptRuntimeInvoke("initFunction",
                            "(Lorg/mozilla/javascript/NativeFunction;"
                            +"I"
                            +"Lorg/mozilla/javascript/Scriptable;"
                            +"Lorg/mozilla/javascript/Context;"
                            +")V");
    }

    private void visitTarget(Node node)
    {
        int label = node.getIntProp(Node.LABEL_PROP, -1);
        if (label == -1) {
            label = acquireLabel();
            node.putIntProp(Node.LABEL_PROP, label);
        }
        markLabel(label);
    }

    private void visitGOTO(Node node, int type, Node child)
    {
        Node target = (Node)(node.getProp(Node.TARGET_PROP));
        int targetLabel = target.getIntProp(Node.LABEL_PROP, -1);
        if (targetLabel == -1) {
            targetLabel = acquireLabel();
            target.putIntProp(Node.LABEL_PROP, targetLabel);
        }
        int fallThruLabel = acquireLabel();

        if ((type == TokenStream.IFEQ) || (type == TokenStream.IFNE)) {
            if (child == null) {
                // can have a null child from visitSwitch which
                // has already generated the code for the child
                // and just needs the GOTO code emitted
                addScriptRuntimeInvoke("toBoolean",
                                       "(Ljava/lang/Object;)Z");
                if (type == TokenStream.IFEQ)
                    addByteCode(ByteCode.IFNE, targetLabel);
                else
                    addByteCode(ByteCode.IFEQ, targetLabel);
            }
            else {
                if (type == TokenStream.IFEQ)
                    generateCodeFromNode(child, node, targetLabel, fallThruLabel);
                else
                    generateCodeFromNode(child, node, fallThruLabel, targetLabel);
                if (!childIsBoolean(child)) {
                    addScriptRuntimeInvoke("toBoolean",
                                           "(Ljava/lang/Object;)Z");
                    if (type == TokenStream.IFEQ)
                        addByteCode(ByteCode.IFNE, targetLabel);
                    else
                        addByteCode(ByteCode.IFEQ, targetLabel);
                }
            }
        }
        else {
            while (child != null) {
                generateCodeFromNode(child, node, -1, -1);
                child = child.getNext();
            }
            if (type == TokenStream.JSR)
                addByteCode(ByteCode.JSR, targetLabel);
            else
                addByteCode(ByteCode.GOTO, targetLabel);
        }
        markLabel(fallThruLabel);
    }

    private void visitEnumInit(Node node, Node child)
    {
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }
        aload(variableObjectLocal);
        addScriptRuntimeInvoke("initEnum",
                               "(Ljava/lang/Object;"
                               +"Lorg/mozilla/javascript/Scriptable;"
                               +")Ljava/lang/Object;");
        short x = getNewWordLocal();
        astore(x);
        node.putIntProp(Node.LOCAL_PROP, x);
    }

    private void visitEnumNext(Node node, Node child)
    {
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }
        Node init = (Node) node.getProp(Node.ENUM_PROP);
        int local = init.getExistingIntProp(Node.LOCAL_PROP);
        aload((short)local);
        addScriptRuntimeInvoke("nextEnum",
                               "(Ljava/lang/Object;)Ljava/lang/Object;");
    }

    private void visitEnumDone(Node node, Node child)
    {
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }
        Node init = (Node) node.getProp(Node.ENUM_PROP);
        int local = init.getExistingIntProp(Node.LOCAL_PROP);
        releaseWordLocal((short)local);
    }

    private void visitEnterWith(Node node, Node child)
    {
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }
        aload(variableObjectLocal);
        addScriptRuntimeInvoke("enterWith",
                               "(Ljava/lang/Object;"
                               +"Lorg/mozilla/javascript/Scriptable;"
                               +")Lorg/mozilla/javascript/Scriptable;");
        astore(variableObjectLocal);
    }

    private void visitLeaveWith(Node node, Node child)
    {
        aload(variableObjectLocal);
        addScriptRuntimeInvoke("leaveWith",
                               "(Lorg/mozilla/javascript/Scriptable;"
                               +")Lorg/mozilla/javascript/Scriptable;");
        astore(variableObjectLocal);
    }

    private void resetTargets(Node node)
    {
        if (node.getType() == TokenStream.TARGET) {
            node.removeProp(Node.LABEL_PROP);
        }
        Node child = node.getFirstChild();
        while (child != null) {
            resetTargets(child);
            child = child.getNext();
        }
    }

    private void visitCall(Node node, int type, Node child)
    {
        /*
         * Generate code for call.
         */

        Node chelsea = child;      // remember the first child for later
        OptFunctionNode
            target = (OptFunctionNode)node.getProp(Node.DIRECTCALL_PROP);
        if (target != null) {
            generateCodeFromNode(child, node, -1, -1);
            int regularCall = acquireLabel();

            int directTargetIndex = target.getDirectTargetIndex();
            addByteCode(ByteCode.ALOAD_0);
            classFile.add(ByteCode.GETFIELD, generatedClassName,
                          MAIN_SCRIPT_FIELD,
                          mainCodegen.generatedClassSignature);
            classFile.add(ByteCode.GETFIELD, mainCodegen.generatedClassName,
                          getDirectTargetFieldName(directTargetIndex),
                          classNameToSignature(target.getClassName()));

            short stackHeight = classFile.getStackTop();

            addByteCode(ByteCode.DUP2);
            addByteCode(ByteCode.IF_ACMPNE, regularCall);
            addByteCode(ByteCode.SWAP);
            addByteCode(ByteCode.POP);

            if (!itsUseDynamicScope) {
                addByteCode(ByteCode.DUP);
                classFile.addInvoke(ByteCode.INVOKEINTERFACE,
                                    "org/mozilla/javascript/Scriptable",
                                    "getParentScope",
                                    "()Lorg/mozilla/javascript/Scriptable;");
            } else {
                aload(variableObjectLocal);
            }
            aload(contextLocal);
            addByteCode(ByteCode.SWAP);

            if (type == TokenStream.NEW)
                addByteCode(ByteCode.ACONST_NULL);
            else {
                child = child.getNext();
                generateCodeFromNode(child, node, -1, -1);
            }
/*
    Remember that directCall parameters are paired in 1 aReg and 1 dReg
    If the argument is an incoming arg, just pass the orginal pair thru.
    Else, if the argument is known to be typed 'Number', pass Void.TYPE
    in the aReg and the number is the dReg
    Else pass the JS object in the aReg and 0.0 in the dReg.
*/
            child = child.getNext();
            while (child != null) {
                boolean handled = false;
                if ((child.getType() == TokenStream.GETVAR)
                        && inDirectCallFunction) {
                    OptLocalVariable lVar
                        = (OptLocalVariable)(child.getProp(Node.VARIABLE_PROP));
                    if (lVar != null && lVar.isParameter()) {
                        handled = true;
                        aload(lVar.getJRegister());
                        dload((short)(lVar.getJRegister() + 1));
                    }
                }
                if (!handled) {
                    int childNumberFlag
                                = child.getIntProp(Node.ISNUMBER_PROP, -1);
                    if (childNumberFlag == Node.BOTH) {
                        classFile.add(ByteCode.GETSTATIC,
                                "java/lang/Void",
                                "TYPE",
                                "Ljava/lang/Class;");
                        generateCodeFromNode(child, node, -1, -1);
                    }
                    else {
                        generateCodeFromNode(child, node, -1, -1);
                        push(0.0);
                    }
                }
                resetTargets(child);
                child = child.getNext();
            }

            classFile.add(ByteCode.GETSTATIC,
                    "org/mozilla/javascript/ScriptRuntime",
                    "emptyArgs", "[Ljava/lang/Object;");

            addVirtualInvoke(target.getClassName(),
                             (type == TokenStream.NEW)
                                 ? "constructDirect" : "callDirect",
                             target.getDirectCallMethodSignature());

            int beyond = acquireLabel();
            addByteCode(ByteCode.GOTO, beyond);
            markLabel(regularCall, stackHeight);
            addByteCode(ByteCode.POP);

            visitRegularCall(node, type, chelsea, true);
            markLabel(beyond);
        }
        else {
            visitRegularCall(node, type, chelsea, false);
        }
   }


    private String getSimpleCallName(Node callNode)
    {
    /*
        Find call trees that look like this :
        (they arise from simple function invocations)

            CALL                     <-- this is the callNode node
                GETPROP
                    NEWTEMP [USES: 1]
                        GETBASE 'name'
                    STRING 'name'
                GETTHIS
                    USETEMP [TEMP: NEWTEMP [USES: 1]]
                <-- arguments would be here

        and return the name found.

    */
        Node callBase = callNode.getFirstChild();
        if (callBase.getType() == TokenStream.GETPROP) {
            Node callBaseChild = callBase.getFirstChild();
            if (callBaseChild.getType() == TokenStream.NEWTEMP) {
                Node callBaseID = callBaseChild.getNext();
                Node tempChild = callBaseChild.getFirstChild();
                if (tempChild.getType() == TokenStream.GETBASE) {
                    String functionName = tempChild.getString();
                    if (callBaseID != null
                        && callBaseID.getType() == TokenStream.STRING)
                    {
                        if (functionName.equals(callBaseID.getString())) {
                            Node thisChild = callBase.getNext();
                            if (thisChild.getType() == TokenStream.GETTHIS) {
                                Node useChild = thisChild.getFirstChild();
                                if (useChild.getType() == TokenStream.USETEMP) {
                                    if (useChild.getProp(Node.TEMP_PROP)
                                         == callBaseChild)
                                    {
                                        return functionName;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private void constructArgArray(int argCount)
    {
        if (argCount == 0) {
            if (itsZeroArgArray >= 0)
                aload(itsZeroArgArray);
            else {
                classFile.add(ByteCode.GETSTATIC,
                        "org/mozilla/javascript/ScriptRuntime",
                        "emptyArgs", "[Ljava/lang/Object;");
            }
        }
        else {
            if (argCount == 1) {
                if (itsOneArgArray >= 0)
                    aload(itsOneArgArray);
                else {
                    push(1);
                    addByteCode(ByteCode.ANEWARRAY, "java/lang/Object");
                }
            }
            else {
                push(argCount);
                addByteCode(ByteCode.ANEWARRAY, "java/lang/Object");
            }
        }
    }

    private void visitRegularCall(Node node, int type,
                                  Node child, boolean firstArgDone)
    {
        /*
         * Generate code for call.
         *
         * push <arity>
         * anewarray Ljava/lang/Object;
         * // "initCount" instances of code from here...
         * dup
         * push <i>
         * <gen code for child>
         * aastore
         * //...to here
         * invokestatic call
         */

        OptFunctionNode target = (OptFunctionNode)node.getProp(Node.DIRECTCALL_PROP);
        Node chelsea = child;
        int childCount = 0;
        int argSkipCount = (type == TokenStream.NEW) ? 1 : 2;
        while (child != null) {
            childCount++;
            child = child.getNext();
        }

        child = chelsea;    // re-start the iterator from the first child,
                    // REMIND - too bad we couldn't just back-patch the count ?

        int argIndex = -argSkipCount;
        if (firstArgDone && (child != null)) {
            child = child.getNext();
            argIndex++;
            aload(contextLocal);
            addByteCode(ByteCode.SWAP);
        }
        else
            aload(contextLocal);

        if (firstArgDone && (type == TokenStream.NEW))
            constructArgArray(childCount - argSkipCount);

        boolean isSpecialCall = node.getIntProp(Node.SPECIALCALL_PROP, 0) != 0;
        boolean isSimpleCall = false;
        String simpleCallName = null;
        if (!firstArgDone && type != TokenStream.NEW) {
            simpleCallName = getSimpleCallName(node);
            if (simpleCallName != null && !isSpecialCall) {
                isSimpleCall = true;
                push(simpleCallName);
                aload(variableObjectLocal);
                child = child.getNext().getNext();
                argIndex = 0;
                constructArgArray(childCount - argSkipCount);
            }
        }

        while (child != null) {
            if (argIndex < 0)       // not moving these arguments to the array
                generateCodeFromNode(child, node, -1, -1);
            else {
                addByteCode(ByteCode.DUP);
                push(argIndex);
                if (target != null) {
/*
    If this has also been a directCall sequence, the Number flag will
    have remained set for any parameter so that the values could be
    copied directly into the outgoing args. Here we want to force it
    to be treated as not in a Number context, so we set the flag off.
*/
                    boolean handled = false;
                    if ((child.getType() == TokenStream.GETVAR)
                            && inDirectCallFunction) {
                        OptLocalVariable lVar
                          = (OptLocalVariable)(child.getProp(Node.VARIABLE_PROP));
                        if (lVar != null && lVar.isParameter()) {
                            child.removeProp(Node.ISNUMBER_PROP);
                            generateCodeFromNode(child, node, -1, -1);
                            handled = true;
                        }
                    }
                    if (!handled) {
                        int childNumberFlag
                                = child.getIntProp(Node.ISNUMBER_PROP, -1);
                        if (childNumberFlag == Node.BOTH) {
                            addByteCode(ByteCode.NEW,"java/lang/Double");
                            addByteCode(ByteCode.DUP);
                            generateCodeFromNode(child, node, -1, -1);
                            addDoubleConstructor();
                        }
                        else
                            generateCodeFromNode(child, node, -1, -1);
                    }
                }
                else
                    generateCodeFromNode(child, node, -1, -1);
                addByteCode(ByteCode.AASTORE);
            }
            argIndex++;
            if (argIndex == 0) {
                constructArgArray(childCount - argSkipCount);
            }
            child = child.getNext();
        }

        String className;
        String methodNameNewObj;
        String methodNameCall;
        String callSignature;

        if (isSpecialCall) {
            className        = "org/mozilla/javascript/ScriptRuntime";
            methodNameNewObj = "newObjectSpecial";
            methodNameCall   = "callSpecial";
            if (type != TokenStream.NEW) {
                callSignature    = "(Lorg/mozilla/javascript/Context;" +
                                     "Ljava/lang/Object;" +
                                     "Ljava/lang/Object;" +
                                     "[Ljava/lang/Object;" +
                                     "Lorg/mozilla/javascript/Scriptable;" +
                                     "Lorg/mozilla/javascript/Scriptable;" +
                                     "Ljava/lang/String;I)";   // filename & linenumber
                aload(thisObjLocal);
                aload(variableObjectLocal);
                push(itsSourceFile == null ? "" : itsSourceFile);
                push(itsLineNumber);
            } else {
                callSignature    = "(Lorg/mozilla/javascript/Context;" +
                                    "Ljava/lang/Object;" +
                                    "[Ljava/lang/Object;" +
                                    "Lorg/mozilla/javascript/Scriptable;)";
                aload(variableObjectLocal);
            }
        } else {
            methodNameNewObj = "newObject";
            if (isSimpleCall) {
                callSignature  = "(Lorg/mozilla/javascript/Context;" +
                                  "Ljava/lang/String;" +
                                  "Lorg/mozilla/javascript/Scriptable;" +
                                  "[Ljava/lang/Object;)";
                methodNameCall = "callSimple";
                className      = "org/mozilla/javascript/optimizer/OptRuntime";
            } else {
                aload(variableObjectLocal);
                if (type == TokenStream.NEW) {
                    callSignature    = "(Lorg/mozilla/javascript/Context;" +
                                        "Ljava/lang/Object;" +
                                        "[Ljava/lang/Object;" +
                                        "Lorg/mozilla/javascript/Scriptable;)";
                } else {
                    callSignature    = "(Lorg/mozilla/javascript/Context;" +
                                        "Ljava/lang/Object;" +
                                        "Ljava/lang/Object;" +
                                        "[Ljava/lang/Object;" +
                                        "Lorg/mozilla/javascript/Scriptable;)";
                }
                methodNameCall   = "call";
                className        = "org/mozilla/javascript/ScriptRuntime";
            }
        }

        if (type == TokenStream.NEW) {
            addStaticInvoke(className,
                            methodNameNewObj,
                            callSignature
                            +"Lorg/mozilla/javascript/Scriptable;");
        } else {
            addStaticInvoke(className,
                            methodNameCall,
                            callSignature
                            +"Ljava/lang/Object;");
        }
    }

    private void visitStatement(Node node)
    {
        itsLineNumber = node.getLineno();
        if (itsLineNumber == -1)
            return;
        classFile.addLineNumberEntry((short)itsLineNumber);
    }


    private void visitTryCatchFinally(Node node, Node child)
    {
        /* Save the variable object, in case there are with statements
         * enclosed by the try block and we catch some exception.
         * We'll restore it for the catch block so that catch block
         * statements get the right scope.
         */

        // OPT we only need to do this if there are enclosed WITH
        // statements; could statically check and omit this if there aren't any.

        // XXX OPT Maybe instead do syntactic transforms to associate
        // each 'with' with a try/finally block that does the exitwith.

        // For that matter:  Why do we have leavewith?

        // XXX does Java have any kind of MOV(reg, reg)?
        short savedVariableObject = getNewWordLocal();
        aload(variableObjectLocal);
        astore(savedVariableObject);

        /*
         * Generate the code for the tree; most of the work is done in IRFactory
         * and NodeTransformer;  Codegen just adds the java handlers for the
         * javascript catch and finally clauses.  */
        // need to set the stack top to 1 to account for the incoming exception
        int startLabel = acquireLabel();
        markLabel(startLabel, (short)1);

        visitStatement(node);
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }

        Node catchTarget = (Node)node.getProp(Node.TARGET_PROP);
        Node finallyTarget = (Node)node.getProp(Node.FINALLY_PROP);

        // control flow skips the handlers
        int realEnd = acquireLabel();
        addByteCode(ByteCode.GOTO, realEnd);


        // javascript handler; unwrap exception and GOTO to javascript
        // catch area.
        if (catchTarget != null) {
            // get the label to goto
            int catchLabel = catchTarget.getExistingIntProp(Node.LABEL_PROP);

            generateCatchBlock(JAVASCRIPTEXCEPTION, savedVariableObject,
                               catchLabel, startLabel);
            /*
             * catch WrappedExceptions, see if they are wrapped
             * JavaScriptExceptions. Otherwise, rethrow.
             */
            generateCatchBlock(WRAPPEDEXCEPTION, savedVariableObject,
                               catchLabel, startLabel);

            /*
                we also need to catch EcmaErrors and feed the
                associated error object to the handler
            */
            int jsHandler = acquireLabel();
            classFile.markHandler(jsHandler);
            short exceptionObject = getNewWordLocal();
            astore(exceptionObject);
            aload(savedVariableObject);
            astore(variableObjectLocal);
            aload(exceptionObject);
            addVirtualInvoke("org/mozilla/javascript/EcmaError",
                             "getErrorObject",
                             "()Lorg/mozilla/javascript/Scriptable;");
            releaseWordLocal(exceptionObject);
            addByteCode(ByteCode.GOTO, catchLabel);
            classFile.addExceptionHandler
                (startLabel, catchLabel, jsHandler,
                 "org/mozilla/javascript/EcmaError");


        }

        // finally handler; catch all exceptions, store to a local; JSR to
        // the finally, then re-throw.
        if (finallyTarget != null) {
            int finallyHandler = acquireLabel();
            classFile.markHandler(finallyHandler);

            // reset the variable object local
            aload(savedVariableObject);
            astore(variableObjectLocal);

            short exnLocal = itsLocalAllocationBase++;
            astore(exnLocal);

            // get the label to JSR to
            int finallyLabel =
                finallyTarget.getExistingIntProp(Node.LABEL_PROP);
            addByteCode(ByteCode.JSR, finallyLabel);

            // rethrow
            aload(exnLocal);
            addByteCode(ByteCode.ATHROW);

            // mark the handler
            classFile.addExceptionHandler(startLabel, finallyLabel,
                                          finallyHandler, null); // catch any
        }
        releaseWordLocal(savedVariableObject);
        markLabel(realEnd);
    }

    private final int JAVASCRIPTEXCEPTION = 0;
    private final int WRAPPEDEXCEPTION    = 1;

    private void generateCatchBlock(int exceptionType,
                                    short savedVariableObject,
                                    int catchLabel,
                                    int startLabel)
    {
        int handler = acquireLabel();
        classFile.markHandler(handler);

        // MS JVM gets cranky if the exception object is left on the stack
        short exceptionObject = getNewWordLocal();
        astore(exceptionObject);

        // reset the variable object local
        aload(savedVariableObject);
        astore(variableObjectLocal);

        aload(exceptionObject);
        releaseWordLocal(exceptionObject);

        if (exceptionType == JAVASCRIPTEXCEPTION) {
            // unwrap the exception...
            addScriptRuntimeInvoke(
                "unwrapJavaScriptException",
                "(Lorg/mozilla/javascript/JavaScriptException;"
                +")Ljava/lang/Object;");
        } else {
            // unwrap the exception...
            addScriptRuntimeInvoke(
                "unwrapWrappedException",
                "(Lorg/mozilla/javascript/WrappedException;"
                +")Ljava/lang/Object;");
        }


        String exceptionName = exceptionType == JAVASCRIPTEXCEPTION
                               ? "org/mozilla/javascript/JavaScriptException"
                               : "org/mozilla/javascript/WrappedException";

        // mark the handler
        classFile.addExceptionHandler(startLabel, catchLabel, handler,
                                      exceptionName);

        addByteCode(ByteCode.GOTO, catchLabel);
    }

    private void visitThrow(Node node, Node child)
    {
        visitStatement(node);
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }

        addByteCode(ByteCode.NEW,
                      "org/mozilla/javascript/JavaScriptException");
        addByteCode(ByteCode.DUP_X1);
        addByteCode(ByteCode.SWAP);
        addSpecialInvoke("org/mozilla/javascript/JavaScriptException",
                         "<init>",
                         "(Ljava/lang/Object;)V");

        addByteCode(ByteCode.ATHROW);
    }

    private void visitReturn(Node node, Node child)
    {
        visitStatement(node);
        if (child != null) {
            do {
                generateCodeFromNode(child, node, -1, -1);
                child = child.getNext();
            } while (child != null);
        } else if (inFunction) {
            pushUndefined();
        } else {
            aload(scriptResultLocal);
        }

        if (epilogueLabel == -1)
            epilogueLabel = classFile.acquireLabel();
        addByteCode(ByteCode.GOTO, epilogueLabel);
    }

    private void visitSwitch(Node node, Node child)
    {
        visitStatement(node);
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }

        // save selector value
        short selector = getNewWordLocal();
        astore(selector);

        ObjArray cases = (ObjArray) node.getProp(Node.CASES_PROP);
        for (int i=0; i < cases.size(); i++) {
            Node thisCase = (Node) cases.get(i);
            Node first = thisCase.getFirstChild();
            generateCodeFromNode(first, thisCase, -1, -1);
            aload(selector);
            addScriptRuntimeInvoke("seqB",
                                   "(Ljava/lang/Object;"
                                   +"Ljava/lang/Object;"
                                   +")Ljava/lang/Boolean;");
            Node target = new Node(TokenStream.TARGET);
            thisCase.replaceChild(first, target);
            generateGOTO(TokenStream.IFEQ, target);
        }

        Node defaultNode = (Node) node.getProp(Node.DEFAULT_PROP);
        if (defaultNode != null) {
            Node defaultTarget = new Node(TokenStream.TARGET);
            defaultNode.getFirstChild().addChildToFront(defaultTarget);
            generateGOTO(TokenStream.GOTO, defaultTarget);
        }

        Node breakTarget = (Node) node.getProp(Node.BREAK_PROP);
        generateGOTO(TokenStream.GOTO, breakTarget);
    }

    private void generateGOTO(int type, Node target)
    {
        Node GOTO = new Node(type);
        GOTO.putProp(Node.TARGET_PROP, target);
        visitGOTO(GOTO, type, null);
    }

    private void visitUnary(Node node, Node child, int trueGOTO, int falseGOTO)
    {
        int op = node.getOperation();
        switch (op) {
          case TokenStream.NOT:
          {
            if (trueGOTO != -1) {
                generateCodeFromNode(child, node, falseGOTO, trueGOTO);
                if (!childIsBoolean(child)) {
                    addScriptRuntimeInvoke("toBoolean",
                                           "(Ljava/lang/Object;"
                                           +")Z");
                    addByteCode(ByteCode.IFNE, falseGOTO);
                    addByteCode(ByteCode.GOTO, trueGOTO);
                }
            }
            else {
                int trueTarget = acquireLabel();
                int falseTarget = acquireLabel();
                int beyond = acquireLabel();
                generateCodeFromNode(child, node, trueTarget, falseTarget);

                if (!childIsBoolean(child)) {
                    addScriptRuntimeInvoke("toBoolean",
                                           "(Ljava/lang/Object;"
                                           +")Z");
                    addByteCode(ByteCode.IFEQ, falseTarget);
                    addByteCode(ByteCode.GOTO, trueTarget);
                }

                markLabel(trueTarget);
                classFile.add(ByteCode.GETSTATIC, "java/lang/Boolean",
                                        "FALSE", "Ljava/lang/Boolean;");
                addByteCode(ByteCode.GOTO, beyond);
                markLabel(falseTarget);
                classFile.add(ByteCode.GETSTATIC, "java/lang/Boolean",
                                        "TRUE", "Ljava/lang/Boolean;");
                markLabel(beyond);
                classFile.adjustStackTop(-1);
            }
            break;
          }

          case TokenStream.TYPEOF:
            visitTypeof(node, child);
            break;

          case TokenStream.VOID:
            generateCodeFromNode(child, node, -1, -1);
            addByteCode(ByteCode.POP);
            pushUndefined();
            break;

          case TokenStream.BITNOT:
            addByteCode(ByteCode.NEW, "java/lang/Double");
            addByteCode(ByteCode.DUP);
            generateCodeFromNode(child, node, -1, -1);
            addScriptRuntimeInvoke("toInt32", "(Ljava/lang/Object;)I");
            push(-1);         // implement ~a as (a ^ -1)
            addByteCode(ByteCode.IXOR);
            addByteCode(ByteCode.I2D);
            addDoubleConstructor();
            break;

          case TokenStream.ADD:
          case TokenStream.SUB:
            addByteCode(ByteCode.NEW, "java/lang/Double");
            addByteCode(ByteCode.DUP);
            generateCodeFromNode(child, node, -1, -1);
            addScriptRuntimeInvoke("toNumber", "(Ljava/lang/Object;)D");
            if (op == TokenStream.SUB) {
                addByteCode(ByteCode.DNEG);
            }
            addDoubleConstructor();
            break;

          default:
            badTree();
        }
    }

    private static boolean childIsBoolean(Node child)
    {
        switch (child.getType()) {
            case TokenStream.UNARYOP:
                return child.getOperation() == TokenStream.NOT;
            case TokenStream.AND:
            case TokenStream.OR:
            case TokenStream.RELOP:
            case TokenStream.EQOP:
                return true;
        }
        return false;
    }

    private void visitTypeof(Node node, Node child)
    {
        if (node.getType() == TokenStream.UNARYOP) {
            generateCodeFromNode(child, node, -1, -1);
            addScriptRuntimeInvoke("typeof",
                                   "(Ljava/lang/Object;"
                                   +")Ljava/lang/String;");
            return;
        }
        String name = node.getString();
        if (hasVarsInRegs) {
            OptLocalVariable lVar = fnCurrent.getVar(name);
            if (lVar != null) {
                if (lVar.isNumber()) {
                    push("number");
                    return;
                }
                visitGetVar(lVar, false, name);
                addScriptRuntimeInvoke("typeof",
                                       "(Ljava/lang/Object;"
                                       +")Ljava/lang/String;");
                return;
            }
        }
        aload(variableObjectLocal);
        push(name);
        addScriptRuntimeInvoke("typeofName",
                               "(Lorg/mozilla/javascript/Scriptable;"
                               +"Ljava/lang/String;"
                               +")Ljava/lang/String;");
    }

    private void visitIncDec(Node node, boolean isInc)
    {
        Node child = node.getFirstChild();
        if (node.getIntProp(Node.ISNUMBER_PROP, -1) != -1) {
            OptLocalVariable lVar
                    = (OptLocalVariable)(child.getProp(Node.VARIABLE_PROP));
            if (lVar.getJRegister() == -1)
                lVar.assignJRegister(getNewWordPairLocal());
            dload(lVar.getJRegister());
            addByteCode(ByteCode.DUP2);
            push(1.0);
            addByteCode((isInc) ? ByteCode.DADD : ByteCode.DSUB);
            dstore(lVar.getJRegister());
        } else {
            OptLocalVariable lVar
                    = (OptLocalVariable)(child.getProp(Node.VARIABLE_PROP));
            String routine = (isInc) ? "postIncrement" : "postDecrement";
            int childType = child.getType();
            if (hasVarsInRegs && childType == TokenStream.GETVAR) {
                if (lVar == null)
                    lVar = fnCurrent.getVar(child.getString());
                if (lVar.getJRegister() == -1)
                    lVar.assignJRegister(getNewWordLocal());
                aload(lVar.getJRegister());
                addByteCode(ByteCode.DUP);
                addScriptRuntimeInvoke(routine,
                                       "(Ljava/lang/Object;"
                                       +")Ljava/lang/Object;");
                astore(lVar.getJRegister());
            } else if (childType == TokenStream.GETPROP) {
                Node getPropChild = child.getFirstChild();
                generateCodeFromNode(getPropChild, node, -1, -1);
                generateCodeFromNode(getPropChild.getNext(), node, -1, -1);
                aload(variableObjectLocal);
                addScriptRuntimeInvoke(routine,
                                       "(Ljava/lang/Object;"
                                       +"Ljava/lang/String;"
                                       +"Lorg/mozilla/javascript/Scriptable;"
                                       +")Ljava/lang/Object;");
            } else if (childType == TokenStream.GETELEM) {
                routine += "Elem";
                Node getPropChild = child.getFirstChild();
                generateCodeFromNode(getPropChild, node, -1, -1);
                generateCodeFromNode(getPropChild.getNext(), node, -1, -1);
                aload(variableObjectLocal);
                addScriptRuntimeInvoke(routine,
                                       "(Ljava/lang/Object;"
                                       +"Ljava/lang/Object;"
                                       +"Lorg/mozilla/javascript/Scriptable;"
                                       +")Ljava/lang/Object;");
            } else {
                aload(variableObjectLocal);
                push(child.getString());          // push name
                addScriptRuntimeInvoke(routine,
                                       "(Lorg/mozilla/javascript/Scriptable;"
                                       +"Ljava/lang/String;"
                                       +")Ljava/lang/Object;");
            }
        }
    }

    private boolean isArithmeticNode(Node node)
    {
        int type = node.getType();
        return (type == TokenStream.SUB)
                  || (type == TokenStream.MOD)
                        || (type == TokenStream.DIV)
                              || (type == TokenStream.MUL);
    }

    private void visitArithmetic(Node node, byte opCode, Node child,
                                 Node parent)
    {
        int childNumberFlag = node.getIntProp(Node.ISNUMBER_PROP, -1);
        if (childNumberFlag != -1) {
            generateCodeFromNode(child, node, -1, -1);
            generateCodeFromNode(child.getNext(), node, -1, -1);
            addByteCode(opCode);
        }
        else {
            boolean childOfArithmetic = isArithmeticNode(parent);
            if (!childOfArithmetic) {
                addByteCode(ByteCode.NEW, "java/lang/Double");
                addByteCode(ByteCode.DUP);
            }
            generateCodeFromNode(child, node, -1, -1);
            if (!isArithmeticNode(child))
                addScriptRuntimeInvoke("toNumber", "(Ljava/lang/Object;)D");
            generateCodeFromNode(child.getNext(), node, -1, -1);
            if (!isArithmeticNode(child.getNext()))
                  addScriptRuntimeInvoke("toNumber", "(Ljava/lang/Object;)D");
            addByteCode(opCode);
            if (!childOfArithmetic) {
                addDoubleConstructor();
            }
        }
    }

    private void visitBitOp(Node node, int type, Node child)
    {
        int childNumberFlag = node.getIntProp(Node.ISNUMBER_PROP, -1);
        if (childNumberFlag == -1) {
            addByteCode(ByteCode.NEW, "java/lang/Double");
            addByteCode(ByteCode.DUP);
        }
        generateCodeFromNode(child, node, -1, -1);

        // special-case URSH; work with the target arg as a long, so
        // that we can return a 32-bit unsigned value, and call
        // toUint32 instead of toInt32.
        if (type == TokenStream.URSH) {
            addScriptRuntimeInvoke("toUint32", "(Ljava/lang/Object;)J");
            generateCodeFromNode(child.getNext(), node, -1, -1);
            addScriptRuntimeInvoke("toInt32", "(Ljava/lang/Object;)I");
            // Looks like we need to explicitly mask the shift to 5 bits -
            // LUSHR takes 6 bits.
            push(31);
            addByteCode(ByteCode.IAND);
            addByteCode(ByteCode.LUSHR);
            addByteCode(ByteCode.L2D);
            addDoubleConstructor();
            return;
        }
        if (childNumberFlag == -1) {
            addScriptRuntimeInvoke("toInt32", "(Ljava/lang/Object;)I");
            generateCodeFromNode(child.getNext(), node, -1, -1);
            addScriptRuntimeInvoke("toInt32", "(Ljava/lang/Object;)I");
        }
        else {
            addScriptRuntimeInvoke("toInt32", "(D)I");
            generateCodeFromNode(child.getNext(), node, -1, -1);
            addScriptRuntimeInvoke("toInt32", "(D)I");
        }
        switch (type) {
          case TokenStream.BITOR:
            addByteCode(ByteCode.IOR);
            break;
          case TokenStream.BITXOR:
            addByteCode(ByteCode.IXOR);
            break;
          case TokenStream.BITAND:
            addByteCode(ByteCode.IAND);
            break;
          case TokenStream.RSH:
            addByteCode(ByteCode.ISHR);
            break;
          case TokenStream.LSH:
            addByteCode(ByteCode.ISHL);
            break;
          default:
            badTree();
        }
        addByteCode(ByteCode.I2D);
        if (childNumberFlag == -1) {
            addDoubleConstructor();
        }
    }

    private boolean nodeIsDirectCallParameter(Node node)
    {
        if (node.getType() == TokenStream.GETVAR) {
            OptLocalVariable lVar
                    = (OptLocalVariable)(node.getProp(Node.VARIABLE_PROP));
            if (lVar != null && lVar.isParameter() && inDirectCallFunction &&
                !itsForcedObjectParameters)
            {
                return true;
            }
        }
        return false;
    }

    private void genSimpleCompare(int op, int trueGOTO, int falseGOTO)
    {
        switch (op) {
            case TokenStream.LE :
                addByteCode(ByteCode.DCMPG);
                addByteCode(ByteCode.IFLE, trueGOTO);
                break;
            case TokenStream.GE :
                addByteCode(ByteCode.DCMPL);
                addByteCode(ByteCode.IFGE, trueGOTO);
                break;
            case TokenStream.LT :
                addByteCode(ByteCode.DCMPG);
                addByteCode(ByteCode.IFLT, trueGOTO);
                break;
            case TokenStream.GT :
                addByteCode(ByteCode.DCMPL);
                addByteCode(ByteCode.IFGT, trueGOTO);
                break;
        }
        if (falseGOTO != -1)
            addByteCode(ByteCode.GOTO, falseGOTO);
    }

    private void visitGOTOingRelOp(Node node, Node child, Node parent,
                                   int trueGOTO, int falseGOTO)
    {
        int op = node.getOperation();
        int childNumberFlag = node.getIntProp(Node.ISNUMBER_PROP, -1);
        if (childNumberFlag == Node.BOTH) {
            generateCodeFromNode(child, node, -1, -1);
            generateCodeFromNode(child.getNext(), node, -1, -1);
            genSimpleCompare(op, trueGOTO, falseGOTO);
        } else {
            if (op == TokenStream.INSTANCEOF) {
                aload(variableObjectLocal);
                generateCodeFromNode(child, node, -1, -1);
                generateCodeFromNode(child.getNext(), node, -1, -1);
                addScriptRuntimeInvoke(
                    "instanceOf",
                    "(Lorg/mozilla/javascript/Scriptable;"
                    +"Ljava/lang/Object;"
                    +"Ljava/lang/Object;"
                    +")Z");
                addByteCode(ByteCode.IFNE, trueGOTO);
                addByteCode(ByteCode.GOTO, falseGOTO);
            } else if (op == TokenStream.IN) {
                generateCodeFromNode(child, node, -1, -1);
                generateCodeFromNode(child.getNext(), node, -1, -1);
                aload(variableObjectLocal);
                addScriptRuntimeInvoke(
                    "in",
                    "(Ljava/lang/Object;"
                    +"Ljava/lang/Object;"
                    +"Lorg/mozilla/javascript/Scriptable;"
                    +")Z");
                addByteCode(ByteCode.IFNE, trueGOTO);
                addByteCode(ByteCode.GOTO, falseGOTO);
            } else {
                Node rChild = child.getNext();
                boolean leftIsDCP = nodeIsDirectCallParameter(child);
                boolean rightIsDCP = nodeIsDirectCallParameter(rChild);
                if (leftIsDCP || rightIsDCP) {
                    if (leftIsDCP) {
                        if (rightIsDCP) {
                            OptLocalVariable lVar1, lVar2;
                            lVar1 = (OptLocalVariable)child.getProp(
                                        Node.VARIABLE_PROP);
                            aload(lVar1.getJRegister());
                            classFile.add(ByteCode.GETSTATIC,
                                    "java/lang/Void",
                                    "TYPE",
                                    "Ljava/lang/Class;");
                            int notNumbersLabel = acquireLabel();
                            addByteCode(ByteCode.IF_ACMPNE, notNumbersLabel);
                            lVar2 = (OptLocalVariable)rChild.getProp(
                                        Node.VARIABLE_PROP);
                            aload(lVar2.getJRegister());
                            classFile.add(ByteCode.GETSTATIC,
                                    "java/lang/Void",
                                    "TYPE",
                                    "Ljava/lang/Class;");
                            addByteCode(ByteCode.IF_ACMPNE, notNumbersLabel);
                            dload((short)(lVar1.getJRegister() + 1));
                            dload((short)(lVar2.getJRegister() + 1));
                            genSimpleCompare(op, trueGOTO, falseGOTO);
                            markLabel(notNumbersLabel);
                            // fall thru to generic handling
                        } else {
                            // just the left child is a DCP, if the right child
                            // is a number it's worth testing the left
                            if (childNumberFlag == Node.RIGHT) {
                                OptLocalVariable lVar1;
                                lVar1 = (OptLocalVariable)child.getProp(
                                            Node.VARIABLE_PROP);
                                aload(lVar1.getJRegister());
                                classFile.add(ByteCode.GETSTATIC,
                                        "java/lang/Void",
                                        "TYPE",
                                        "Ljava/lang/Class;");
                                int notNumbersLabel = acquireLabel();
                                addByteCode(ByteCode.IF_ACMPNE,
                                            notNumbersLabel);
                                dload((short)(lVar1.getJRegister() + 1));
                                generateCodeFromNode(rChild, node, -1, -1);
                                genSimpleCompare(op, trueGOTO, falseGOTO);
                                markLabel(notNumbersLabel);
                                // fall thru to generic handling
                            }
                        }
                    } else {
                        //  just the right child is a DCP, if the left child
                        //  is a number it's worth testing the right
                        if (childNumberFlag == Node.LEFT) {
                            OptLocalVariable lVar2;
                            lVar2 = (OptLocalVariable)rChild.getProp(
                                        Node.VARIABLE_PROP);
                            aload(lVar2.getJRegister());
                            classFile.add(ByteCode.GETSTATIC,
                                    "java/lang/Void",
                                    "TYPE",
                                    "Ljava/lang/Class;");
                            int notNumbersLabel = acquireLabel();
                            addByteCode(ByteCode.IF_ACMPNE, notNumbersLabel);
                            generateCodeFromNode(child, node, -1, -1);
                            dload((short)(lVar2.getJRegister() + 1));
                            genSimpleCompare(op, trueGOTO, falseGOTO);
                            markLabel(notNumbersLabel);
                            // fall thru to generic handling
                        }
                    }
                }
                generateCodeFromNode(child, node, -1, -1);
                generateCodeFromNode(rChild, node, -1, -1);
                if (childNumberFlag == -1) {
                    if (op == TokenStream.GE || op == TokenStream.GT) {
                        addByteCode(ByteCode.SWAP);
                    }
                    String routine = ((op == TokenStream.LT)
                              || (op == TokenStream.GT)) ? "cmp_LT" : "cmp_LE";
                    addScriptRuntimeInvoke(routine,
                                           "(Ljava/lang/Object;"
                                           +"Ljava/lang/Object;"
                                           +")I");
                } else {
                    boolean doubleThenObject = (childNumberFlag == Node.LEFT);
                    if (op == TokenStream.GE || op == TokenStream.GT) {
                        if (doubleThenObject) {
                            addByteCode(ByteCode.DUP_X2);
                            addByteCode(ByteCode.POP);
                            doubleThenObject = false;
                        } else {
                            addByteCode(ByteCode.DUP2_X1);
                            addByteCode(ByteCode.POP2);
                            doubleThenObject = true;
                        }
                    }
                    String routine = ((op == TokenStream.LT)
                             || (op == TokenStream.GT)) ? "cmp_LT" : "cmp_LE";
                    if (doubleThenObject)
                        addOptRuntimeInvoke(routine, "(DLjava/lang/Object;)I");
                    else
                        addOptRuntimeInvoke(routine, "(Ljava/lang/Object;D)I");
                }
                addByteCode(ByteCode.IFNE, trueGOTO);
                addByteCode(ByteCode.GOTO, falseGOTO);
            }
        }
    }

    private void visitRelOp(Node node, Node child, Node parent)
    {
        /*
            this is the version that returns an Object result
        */
        int op = node.getOperation();
        int childNumberFlag = node.getIntProp(Node.ISNUMBER_PROP, -1);
        if (childNumberFlag == Node.BOTH
                || op == TokenStream.INSTANCEOF
                || op == TokenStream.IN)
        {
            if (op == TokenStream.INSTANCEOF)
                aload(variableObjectLocal);
            generateCodeFromNode(child, node, -1, -1);
            generateCodeFromNode(child.getNext(), node, -1, -1);
            int trueGOTO = acquireLabel();
            int skip = acquireLabel();
            if (op == TokenStream.INSTANCEOF) {
                addScriptRuntimeInvoke(
                    "instanceOf",
                    "(Lorg/mozilla/javascript/Scriptable;"
                    +"Ljava/lang/Object;"
                    +"Ljava/lang/Object;"
                    +")Z");
                addByteCode(ByteCode.IFNE, trueGOTO);
            } else if (op == TokenStream.IN) {
                aload(variableObjectLocal);
                addScriptRuntimeInvoke(
                    "in",
                    "(Ljava/lang/Object;"
                    +"Ljava/lang/Object;"
                    +"Lorg/mozilla/javascript/Scriptable;"
                    +")Z");
                addByteCode(ByteCode.IFNE, trueGOTO);
            } else {
                genSimpleCompare(op, trueGOTO, -1);
            }
            classFile.add(ByteCode.GETSTATIC, "java/lang/Boolean",
                                    "FALSE", "Ljava/lang/Boolean;");
            addByteCode(ByteCode.GOTO, skip);
            markLabel(trueGOTO);
            classFile.add(ByteCode.GETSTATIC, "java/lang/Boolean",
                                    "TRUE", "Ljava/lang/Boolean;");
            markLabel(skip);
            classFile.adjustStackTop(-1);   // only have 1 of true/false
        }
        else {
            String routine = ((op == TokenStream.LT)
                     || (op == TokenStream.GT)) ? "cmp_LTB" : "cmp_LEB";
            generateCodeFromNode(child, node, -1, -1);
            generateCodeFromNode(child.getNext(), node, -1, -1);
            if (childNumberFlag == -1) {
                if (op == TokenStream.GE || op == TokenStream.GT) {
                    addByteCode(ByteCode.SWAP);
                }
                addScriptRuntimeInvoke(routine,
                                       "(Ljava/lang/Object;"
                                       +"Ljava/lang/Object;"
                                       +")Ljava/lang/Boolean;");
            }
            else {
                boolean doubleThenObject = (childNumberFlag == Node.LEFT);
                if (op == TokenStream.GE || op == TokenStream.GT) {
                    if (doubleThenObject) {
                        addByteCode(ByteCode.DUP_X2);
                        addByteCode(ByteCode.POP);
                        doubleThenObject = false;
                    }
                    else {
                        addByteCode(ByteCode.DUP2_X1);
                        addByteCode(ByteCode.POP2);
                        doubleThenObject = true;
                    }
                }
                if (doubleThenObject)
                    addOptRuntimeInvoke(routine,
                                        "(DLjava/lang/Object;"
                                        +")Ljava/lang/Boolean;");
                else
                    addOptRuntimeInvoke(routine,
                                        "(Ljava/lang/Object;D"
                                        +")Ljava/lang/Boolean;");
            }
        }
    }

    private Node getConvertToObjectOfNumberNode(Node node)
    {
        if (node.getType() == TokenStream.CONVERT) {
            Object toType = node.getProp(Node.TYPE_PROP);
            if (toType == ScriptRuntime.ObjectClass) {
                Node convertChild = node.getFirstChild();
                if (convertChild.getType() == TokenStream.NUMBER)
                    return convertChild;
            }
        }
        return null;
    }

    private void visitEqOp(Node node, Node child, Node parent, int trueGOTO,
                           int falseGOTO)
    {
        int op = node.getOperation();
        Node rightChild = child.getNext();
        boolean isStrict = op == TokenStream.SHEQ ||
                           op == TokenStream.SHNE;
        if (trueGOTO == -1) {
            if (rightChild.getType() == TokenStream.PRIMARY &&
                rightChild.getOperation() == TokenStream.NULL)
            {
                generateCodeFromNode(child, node, -1, -1);
                if (isStrict) {
                    addByteCode(ByteCode.IFNULL, 9);
                } else {
                    addByteCode(ByteCode.DUP);
                    addByteCode(ByteCode.IFNULL, 15);
                    pushUndefined();
                    addByteCode(ByteCode.IF_ACMPEQ, 10);
                }
                if ((op == TokenStream.EQ) || (op == TokenStream.SHEQ))
                    classFile.add(ByteCode.GETSTATIC, "java/lang/Boolean",
                                            "FALSE", "Ljava/lang/Boolean;");
                else
                    classFile.add(ByteCode.GETSTATIC, "java/lang/Boolean",
                                            "TRUE", "Ljava/lang/Boolean;");
                if (isStrict) {
                    addByteCode(ByteCode.GOTO, 6);
                } else {
                    addByteCode(ByteCode.GOTO, 7);
                    addByteCode(ByteCode.POP);
                }
                if ((op == TokenStream.EQ) || (op == TokenStream.SHEQ))
                    classFile.add(ByteCode.GETSTATIC, "java/lang/Boolean",
                                            "TRUE", "Ljava/lang/Boolean;");
                else
                    classFile.add(ByteCode.GETSTATIC, "java/lang/Boolean",
                                            "FALSE", "Ljava/lang/Boolean;");
                return;
            }

            generateCodeFromNode(child, node, -1, -1);
            generateCodeFromNode(child.getNext(), node, -1, -1);

            // JavaScript 1.2 uses shallow equality for == and !=
            String name;
            switch (op) {
              case TokenStream.EQ:
                name = version == Context.VERSION_1_2 ? "seqB" : "eqB";
                break;

              case TokenStream.NE:
                name = version == Context.VERSION_1_2 ? "sneB" : "neB";
                break;

              case TokenStream.SHEQ:
                name = "seqB";
                break;

              case TokenStream.SHNE:
                name = "sneB";
                break;

              default:
                name = null;
                badTree();
            }
            addScriptRuntimeInvoke(name,
                                   "(Ljava/lang/Object;"
                                   +"Ljava/lang/Object;"
                                   +")Ljava/lang/Boolean;");
        }
        else {
            if (rightChild.getType() == TokenStream.PRIMARY &&
                rightChild.getOperation() == TokenStream.NULL)
            {
                if (op != TokenStream.EQ && op != TokenStream.SHEQ) {
                    // invert true and false.
                    int temp = trueGOTO;
                    trueGOTO = falseGOTO;
                    falseGOTO = temp;
                }

                generateCodeFromNode(child, node, -1, -1);
                if (isStrict) {
                    addByteCode(ByteCode.IFNULL, trueGOTO);
                    addByteCode(ByteCode.GOTO, falseGOTO);
                    return;
                }
                /*
                    since we have to test for null && undefined we end up
                    having to push the operand twice and so have to GOTO to
                    a pop site if the first test passes.
                    We can avoid that for operands that are 'simple', i.e.
                    don't generate a lot of code and don't have side-effects.
                    For now, 'simple' means GETVAR
                */
                boolean simpleChild = (child.getType() == TokenStream.GETVAR);
                if (!simpleChild) addByteCode(ByteCode.DUP);
                int popGOTO = acquireLabel();
                addByteCode(ByteCode.IFNULL,
                                (simpleChild) ? trueGOTO : popGOTO);
                short popStack = classFile.getStackTop();
                if (simpleChild) generateCodeFromNode(child, node, -1, -1);
                pushUndefined();
                addByteCode(ByteCode.IF_ACMPEQ, trueGOTO);
                addByteCode(ByteCode.GOTO, falseGOTO);
                if (!simpleChild) {
                    markLabel(popGOTO, popStack);
                    addByteCode(ByteCode.POP);
                    addByteCode(ByteCode.GOTO, trueGOTO);
                }
                return;
            }

            Node rChild = child.getNext();

            if (nodeIsDirectCallParameter(child)) {
                Node convertChild = getConvertToObjectOfNumberNode(rChild);
                if (convertChild != null) {
                    OptLocalVariable lVar1
                        = (OptLocalVariable)(child.getProp(Node.VARIABLE_PROP));
                    aload(lVar1.getJRegister());
                    classFile.add(ByteCode.GETSTATIC,
                            "java/lang/Void",
                            "TYPE",
                            "Ljava/lang/Class;");
                    int notNumbersLabel = acquireLabel();
                    addByteCode(ByteCode.IF_ACMPNE, notNumbersLabel);
                    dload((short)(lVar1.getJRegister() + 1));
                    push(convertChild.getDouble());
                    addByteCode(ByteCode.DCMPL);
                    if (op == TokenStream.EQ)
                        addByteCode(ByteCode.IFEQ, trueGOTO);
                    else
                        addByteCode(ByteCode.IFNE, trueGOTO);
                    addByteCode(ByteCode.GOTO, falseGOTO);
                    markLabel(notNumbersLabel);
                    // fall thru into generic handling
                }
            }

            generateCodeFromNode(child, node, -1, -1);
            generateCodeFromNode(rChild, node, -1, -1);

            String name;
            switch (op) {
              case TokenStream.EQ:
                name = version == Context.VERSION_1_2 ? "shallowEq" : "eq";
                addScriptRuntimeInvoke(name,
                                       "(Ljava/lang/Object;"
                                       +"Ljava/lang/Object;"
                                       +")Z");
                break;

              case TokenStream.NE:
                name = version == Context.VERSION_1_2 ? "shallowNeq" : "neq";
                addOptRuntimeInvoke(name,
                                    "(Ljava/lang/Object;"
                                    +"Ljava/lang/Object;"
                                    +")Z");
                break;

              case TokenStream.SHEQ:
                name = "shallowEq";
                addScriptRuntimeInvoke(name,
                                       "(Ljava/lang/Object;"
                                       +"Ljava/lang/Object;"
                                       +")Z");
                break;

              case TokenStream.SHNE:
                name = "shallowNeq";
                addOptRuntimeInvoke(name,
                                    "(Ljava/lang/Object;"
                                    +"Ljava/lang/Object;"
                                    +")Z");
                break;

              default:
                name = null;
                badTree();
            }
            addByteCode(ByteCode.IFNE, trueGOTO);
            addByteCode(ByteCode.GOTO, falseGOTO);
        }
    }

    private void visitLiteral(Node node)
    {
        if (node.getType() == TokenStream.STRING) {
            // just load the string constant
            push(node.getString());
        } else {
            double num = node.getDouble();
            if (node.getIntProp(Node.ISNUMBER_PROP, -1) != -1) {
                push(num);
            }
            else if (itsConstantListSize >= 2000) {
                // There appears to be a limit in the JVM on either the number
                // of static fields in a class or the size of the class
                // initializer. Either way, we can't have any more than 2000
                // statically init'd constants.
                pushAsWrapperObject(num);
            }
            else {
                if (num != num) {
                    // Add NaN object
                    classFile.add(ByteCode.GETSTATIC,
                                  "org/mozilla/javascript/ScriptRuntime",
                                  "NaNobj", "Ljava/lang/Double;");
                } else {
                    String constantName = "jsK_" + addNumberConstant(num);
                    String constantType = getStaticConstantWrapperType(num);
                    classFile.add(
                        ByteCode.GETSTATIC,
                        classFile.fullyQualifiedForm(generatedClassName),
                        constantName, constantType);
                }
            }
        }
    }

   private void visitPrimary(Node node)
   {
        int op = node.getOperation();
        switch (op) {

          case TokenStream.THIS:
            aload(thisObjLocal);
            break;

          case TokenStream.THISFN:
            classFile.add(ByteCode.ALOAD_0);
            break;

          case TokenStream.NULL:
            addByteCode(ByteCode.ACONST_NULL);
            break;

          case TokenStream.TRUE:
            classFile.add(ByteCode.GETSTATIC, "java/lang/Boolean",
                                    "TRUE", "Ljava/lang/Boolean;");
            break;

          case TokenStream.FALSE:
            classFile.add(ByteCode.GETSTATIC, "java/lang/Boolean",
                                    "FALSE", "Ljava/lang/Boolean;");
            break;

          case TokenStream.UNDEFINED:
            pushUndefined();
            break;

          default:
            badTree();
        }
    }

    private void visitObject(Node node)
    {
        int i = node.getExistingIntProp(Node.REGEXP_PROP);
        String fieldName = getRegexpFieldName(i);
        aload(funObjLocal);
        classFile.add(ByteCode.GETFIELD,
                      classFile.fullyQualifiedForm(generatedClassName),
                      fieldName,
                      "Lorg/mozilla/javascript/regexp/NativeRegExp;");
    }

    private void visitName(Node node)
    {
        aload(variableObjectLocal);             // get variable object
        push(node.getString());                 // push name
        addScriptRuntimeInvoke(
            "name",
            "(Lorg/mozilla/javascript/Scriptable;"
            +"Ljava/lang/String;"
            +")Ljava/lang/Object;");
    }

    private void visitSetName(Node node, Node child)
    {
        String name = node.getFirstChild().getString();
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }
        aload(variableObjectLocal);
        push(name);
        addScriptRuntimeInvoke(
            "setName",
            "(Lorg/mozilla/javascript/Scriptable;"
            +"Ljava/lang/Object;"
            +"Lorg/mozilla/javascript/Scriptable;"
            +"Ljava/lang/String;"
            +")Ljava/lang/Object;");
    }

    private void visitGetVar(OptLocalVariable lVar, boolean isNumber,
                             String name)
    {
        // TODO: Clean up use of lVar here and in set.
        if (hasVarsInRegs && lVar == null)
            lVar = fnCurrent.getVar(name);
        if (lVar != null) {
            if (lVar.getJRegister() == -1)
                if (lVar.isNumber())
                    lVar.assignJRegister(getNewWordPairLocal());
                else
                    lVar.assignJRegister(getNewWordLocal());
            if (lVar.isParameter() && inDirectCallFunction &&
                !itsForcedObjectParameters)
            {
/*
    Remember that here the isNumber flag means that we want to
    use the incoming parameter in a Number context, so test the
    object type and convert the value as necessary.

*/
                if (isNumber) {
                    aload(lVar.getJRegister());
                    classFile.add(ByteCode.GETSTATIC,
                            "java/lang/Void",
                            "TYPE",
                            "Ljava/lang/Class;");
                    int isNumberLabel = acquireLabel();
                    int beyond = acquireLabel();
                    addByteCode(ByteCode.IF_ACMPEQ, isNumberLabel);
                    aload(lVar.getJRegister());
                    addScriptRuntimeInvoke("toNumber", "(Ljava/lang/Object;)D");
                    addByteCode(ByteCode.GOTO, beyond);
                    markLabel(isNumberLabel);
                    dload((short)(lVar.getJRegister() + 1));
                    markLabel(beyond);
                } else {
                    aload(lVar.getJRegister());
                    classFile.add(ByteCode.GETSTATIC,
                            "java/lang/Void",
                            "TYPE",
                            "Ljava/lang/Class;");
                    int isNumberLabel = acquireLabel();
                    int beyond = acquireLabel();
                    addByteCode(ByteCode.IF_ACMPEQ, isNumberLabel);
                    aload(lVar.getJRegister());
                    addByteCode(ByteCode.GOTO, beyond);
                    markLabel(isNumberLabel);
                    addByteCode(ByteCode.NEW,"java/lang/Double");
                    addByteCode(ByteCode.DUP);
                    dload((short)(lVar.getJRegister() + 1));
                    addDoubleConstructor();
                    markLabel(beyond);
                }
            } else {
                if (lVar.isNumber())
                    dload(lVar.getJRegister());
                else
                    aload(lVar.getJRegister());
            }
            return;
        }

        aload(variableObjectLocal);
        push(name);
        aload(variableObjectLocal);
        addScriptRuntimeInvoke(
            "getProp",
            "(Ljava/lang/Object;"
            +"Ljava/lang/String;"
            +"Lorg/mozilla/javascript/Scriptable;"
            +")Ljava/lang/Object;");
    }

    private void visitSetVar(Node node, Node child, boolean needValue)
    {
        OptLocalVariable lVar;
        lVar = (OptLocalVariable)(node.getProp(Node.VARIABLE_PROP));
        // XXX is this right? If so, clean up.
        if (hasVarsInRegs && lVar == null)
            lVar = fnCurrent.getVar(child.getString());
        if (lVar != null) {
            generateCodeFromNode(child.getNext(), node, -1, -1);
            if (lVar.getJRegister() == -1) {
                if (lVar.isNumber())
                    lVar.assignJRegister(getNewWordPairLocal());
                else
                    lVar.assignJRegister(getNewWordLocal());
            }
            if (lVar.isParameter()
                        && inDirectCallFunction
                        && !itsForcedObjectParameters) {
                if (node.getIntProp(Node.ISNUMBER_PROP, -1) != -1) {
                    if (needValue) addByteCode(ByteCode.DUP2);
                    aload(lVar.getJRegister());
                    classFile.add(ByteCode.GETSTATIC,
                            "java/lang/Void",
                            "TYPE",
                            "Ljava/lang/Class;");
                    int isNumberLabel = acquireLabel();
                    int beyond = acquireLabel();
                    addByteCode(ByteCode.IF_ACMPEQ, isNumberLabel);
                    addByteCode(ByteCode.NEW,"java/lang/Double");
                    addByteCode(ByteCode.DUP);
                    addByteCode(ByteCode.DUP2_X2);
                    addByteCode(ByteCode.POP2);
                    addDoubleConstructor();
                    astore(lVar.getJRegister());
                    addByteCode(ByteCode.GOTO, beyond);
                    markLabel(isNumberLabel);
                    dstore((short)(lVar.getJRegister() + 1));
                    markLabel(beyond);
                }
                else {
                    if (needValue) addByteCode(ByteCode.DUP);
                    astore(lVar.getJRegister());
                }
            }
            else {
                if (node.getIntProp(Node.ISNUMBER_PROP, -1) != -1) {
                      dstore(lVar.getJRegister());
                      if (needValue) dload(lVar.getJRegister());
                }
                else {
                    astore(lVar.getJRegister());
                    if (needValue) aload(lVar.getJRegister());
                }
            }
            return;
        }

        // default: just treat like any other name lookup
        child.setType(TokenStream.BINDNAME);
        node.setType(TokenStream.SETNAME);
        visitSetName(node, child);
        if (!needValue)
            addByteCode(ByteCode.POP);
    }

    private void visitGetProp(Node node, Node child)
    {
        String s = (String) node.getProp(Node.SPECIAL_PROP_PROP);
        if (s != null) {
            while (child != null) {
                generateCodeFromNode(child, node, -1, -1);
                child = child.getNext();
            }
            aload(variableObjectLocal);
            String runtimeMethod = null;
            if (s.equals("__proto__")) {
                runtimeMethod = "getProto";
            } else if (s.equals("__parent__")) {
                runtimeMethod = "getParent";
            } else {
                badTree();
            }
            addScriptRuntimeInvoke(
                runtimeMethod,
                "(Ljava/lang/Object;"
                +"Lorg/mozilla/javascript/Scriptable;"
                +")Lorg/mozilla/javascript/Scriptable;");
            return;
        }
        Node nameChild = child.getNext();
        /*
            for 'this.foo' we call thisGet which can skip some
            casting overhead.

        */
        generateCodeFromNode(child, node, -1, -1);      // the object
        generateCodeFromNode(nameChild, node, -1, -1);  // the name
        if (nameChild.getType() == TokenStream.STRING) {
            if ((child.getType() == TokenStream.PRIMARY
                 && child.getOperation() == TokenStream.THIS)
                || (child.getType() == TokenStream.NEWTEMP
                    && child.getFirstChild().getType() == TokenStream.PRIMARY
                    && child.getFirstChild().getOperation()
                           == TokenStream.THIS))
            {
                aload(variableObjectLocal);
                addOptRuntimeInvoke(
                    "thisGet",
                    "(Lorg/mozilla/javascript/Scriptable;"
                    +"Ljava/lang/String;"
                    +"Lorg/mozilla/javascript/Scriptable;"
                    +")Ljava/lang/Object;");
            }
            else {
                aload(variableObjectLocal);
                addScriptRuntimeInvoke(
                    "getProp",
                    "(Ljava/lang/Object;"
                    +"Ljava/lang/String;"
                    +"Lorg/mozilla/javascript/Scriptable;"
                    +")Ljava/lang/Object;");
            }
        }
        else {
            aload(variableObjectLocal);
            addScriptRuntimeInvoke(
                "getProp",
                "(Ljava/lang/Object;"
                +"Ljava/lang/String;"
                +"Lorg/mozilla/javascript/Scriptable;"
                +")Ljava/lang/Object;");
        }
    }

    private void visitSetProp(Node node, Node child)
    {
        String s = (String) node.getProp(Node.SPECIAL_PROP_PROP);
        if (s != null) {
            while (child != null) {
                generateCodeFromNode(child, node, -1, -1);
                child = child.getNext();
            }
            aload(variableObjectLocal);
            String runtimeMethod = null;
            if (s.equals("__proto__")) {
                runtimeMethod = "setProto";
            } else if (s.equals("__parent__")) {
                runtimeMethod = "setParent";
            } else {
                badTree();
            }
            addScriptRuntimeInvoke(
                runtimeMethod,
                "(Ljava/lang/Object;"
                +"Ljava/lang/Object;"
                +"Lorg/mozilla/javascript/Scriptable;"
                +")Ljava/lang/Object;");
            return;
        }
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }
        aload(variableObjectLocal);
        addScriptRuntimeInvoke(
            "setProp",
            "(Ljava/lang/Object;"
            +"Ljava/lang/String;"
            +"Ljava/lang/Object;"
            +"Lorg/mozilla/javascript/Scriptable;"
            +")Ljava/lang/Object;");
    }

    private void visitBind(Node node, int type, Node child)
    {
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }
        // Generate code for "ScriptRuntime.bind(varObj, "s")"
        aload(variableObjectLocal);             // get variable object
        push(node.getString());                 // push name
        addScriptRuntimeInvoke(
            type == TokenStream.BINDNAME ? "bind" : "getBase",
            "(Lorg/mozilla/javascript/Scriptable;"
            +"Ljava/lang/String;"
            +")Lorg/mozilla/javascript/Scriptable;");
    }

    private short getLocalFromNode(Node node)
    {
        int local = node.getIntProp(Node.LOCAL_PROP, -1);
        if (local == -1) {
            // for NEWLOCAL & USELOCAL, use the next pre-allocated
            // register, otherwise for NEWTEMP & USETEMP, get the
            // next available from the pool
            local = ((node.getType() == TokenStream.NEWLOCAL)
                                || (node.getType() == TokenStream.USELOCAL)) ?
                            itsLocalAllocationBase++ : getNewWordLocal();

            node.putIntProp(Node.LOCAL_PROP, local);
        }
        return (short)local;
    }

    private void visitNewTemp(Node node, Node child)
    {
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }
        short local = getLocalFromNode(node);
        addByteCode(ByteCode.DUP);
        astore(local);
        if (node.getIntProp(Node.USES_PROP, 0) == 0)
            releaseWordLocal(local);
    }

    private void visitUseTemp(Node node, Node child)
    {
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }
        Node temp = (Node) node.getProp(Node.TEMP_PROP);
        short local = getLocalFromNode(temp);

        // if the temp node has a magic TARGET property,
        // treat it as a RET to that temp.
        if (node.getProp(Node.TARGET_PROP) != null)
            addByteCode(ByteCode.RET, local);
        else
            aload(local);
        int n = temp.getIntProp(Node.USES_PROP, 0);
        if (n <= 1) {
                    releaseWordLocal(local);
            }
        if (n != 0 && n != Integer.MAX_VALUE) {
            temp.putIntProp(Node.USES_PROP, n - 1);
        }
    }

    private void visitNewLocal(Node node, Node child)
    {
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }
        short local = getLocalFromNode(node);
        addByteCode(ByteCode.DUP);
        astore(local);
    }

    private void visitUseLocal(Node node, Node child)
    {
        while (child != null) {
            generateCodeFromNode(child, node, -1, -1);
            child = child.getNext();
        }
        Node temp = (Node) node.getProp(Node.LOCAL_PROP);
        short local = getLocalFromNode(temp);

        // if the temp node has a magic TARGET property,
        // treat it as a RET to that temp.
        if (node.getProp(Node.TARGET_PROP) != null)
            addByteCode(ByteCode.RET, local);
        else
            aload(local);
    }

    private String getStaticConstantWrapperType(double num)
    {
        String constantType;
        int inum = (int)num;
        if (inum == num) {
            if ((byte)inum == inum) {
                constantType = "Ljava/lang/Byte;";
            } else if ((short)inum == inum) {
                constantType = "Ljava/lang/Short;";
            } else {
                constantType = "Ljava/lang/Integer;";
            }
        } else {
            // See comments in push(double)
            //if ((float)num == num) {
            //      constantType = "Ljava/lang/Float;";
            //}
            //else {
                constantType = "Ljava/lang/Double;";
            //}
        }
        return constantType;
    }

    private int addNumberConstant(double num)
    {
        // NaN is provided via ScriptRuntime.NaNobj
        if (num != num) Context.codeBug();
        int N = itsConstantListSize;
        if (N == 0) {
            itsConstantList = new double[128];
        } else {
            double[] array = itsConstantList;
            for (int i = 0; i != N; ++i) {
                if (array[i] == num) { return i; }
            }
            if (N == array.length) {
                array = new double[N * 2];
                System.arraycopy(itsConstantList, 0, array, 0, N);
                itsConstantList = array;
            }
        }
        itsConstantList[N] = num;
        itsConstantListSize = N + 1;
        return N;
    }

    private void startCodeBodyMethod(String methodName, String methodDesc)
    {
        classFile.startMethod(methodName, methodDesc,
                              (short)(ClassFileWriter.ACC_PUBLIC
                                      | ClassFileWriter.ACC_FINAL));

        locals = new boolean[MAX_LOCALS];

        funObjLocal = 0;
        contextLocal = 1;
        variableObjectLocal = 2;
        thisObjLocal = 3;
        localsMax = (short) 4;  // number of parms + "this"
        firstFreeLocal = 4;

        scriptResultLocal = -1;
        argsLocal = -1;
        itsZeroArgArray = -1;
        itsOneArgArray = -1;
        epilogueLabel = -1;
    }

    private void finishMethod(Context cx, OptLocalVariable[] array)
    {
        classFile.stopMethod((short)(localsMax + 1), array);
    }

    private void addByteCode(byte theOpcode)
    {
        classFile.add(theOpcode);
    }

    private void addByteCode(byte theOpcode, int theOperand)
    {
        classFile.add(theOpcode, theOperand);
    }

    private void addByteCode(byte theOpcode, String className)
    {
        classFile.add(theOpcode, className);
    }

    private void addVirtualInvoke(String className, String methodName,
                                  String methodSignature)
    {
        classFile.addInvoke(ByteCode.INVOKEVIRTUAL,
                            className,
                            methodName,
                            methodSignature);
    }

    private void addStaticInvoke(String className, String methodName,
                                 String methodSignature)
    {
        classFile.addInvoke(ByteCode.INVOKESTATIC,
                            className,
                            methodName,
                            methodSignature);
    }

    private void addScriptRuntimeInvoke(String methodName,
                                        String methodSignature)
    {
        classFile.addInvoke(ByteCode.INVOKESTATIC,
                            "org/mozilla/javascript/ScriptRuntime",
                            methodName,
                            methodSignature);
    }

    private void addOptRuntimeInvoke(String methodName,
                                     String methodSignature)
    {
        classFile.addInvoke(ByteCode.INVOKESTATIC,
                            "org/mozilla/javascript/optimizer/OptRuntime",
                            methodName,
                            methodSignature);
    }

    private void addSpecialInvoke(String className, String methodName,
                                  String methodSignature)
    {
        classFile.addInvoke(ByteCode.INVOKESPECIAL,
                            className,
                            methodName,
                            methodSignature);
    }

    private void addDoubleConstructor()
    {
        classFile.addInvoke(ByteCode.INVOKESPECIAL,
                            "java/lang/Double", "<init>", "(D)V");
    }

    private void markLabel(int label)
    {
        classFile.markLabel(label);
    }

    private void markLabel(int label, short stackheight)
    {
        classFile.markLabel(label, stackheight);
    }

    private int acquireLabel()
    {
        return classFile.acquireLabel();
    }

    private void dstore(short local)
    {
        xop(ByteCode.DSTORE_0, ByteCode.DSTORE, local);
    }

    private void istore(short local)
    {
        xop(ByteCode.ISTORE_0, ByteCode.ISTORE, local);
    }

    private void astore(short local)
    {
        xop(ByteCode.ASTORE_0, ByteCode.ASTORE, local);
    }

    private void xop(byte shortOp, byte op, short local)
    {
        switch (local) {
          case 0:
            addByteCode(shortOp);
            break;
          case 1:
            addByteCode((byte)(shortOp + 1));
            break;
          case 2:
            addByteCode((byte)(shortOp + 2));
            break;
          case 3:
            addByteCode((byte)(shortOp + 3));
            break;
          default:
            if (local < 0 || local >= Short.MAX_VALUE)
                throw new RuntimeException("bad local");
            if (local < Byte.MAX_VALUE) {
                addByteCode(op, (byte)local);
            } else {
                // Add wide opcode.
                addByteCode(ByteCode.WIDE);
                addByteCode(op);
                addByteCode((byte)(local >> 8));
                addByteCode((byte)(local & 0xff));
            }
            break;
        }
    }

    private void dload(short local)
    {
        xop(ByteCode.DLOAD_0, ByteCode.DLOAD, local);
    }

    private void iload(short local)
    {
        xop(ByteCode.ILOAD_0, ByteCode.ILOAD, local);
    }

    private void aload(short local)
    {
        xop(ByteCode.ALOAD_0, ByteCode.ALOAD, local);
    }

    private short getNewWordPairLocal()
    {
        short result = firstFreeLocal;
        while (true) {
            if (result >= (MAX_LOCALS - 1))
                break;
            if (!locals[result]
                    && !locals[result + 1])
                break;
            result++;
        }
        if (result < (MAX_LOCALS - 1)) {
            locals[result] = true;
            locals[result + 1] = true;
            if (result == firstFreeLocal) {
                for (int i = firstFreeLocal + 2; i < MAX_LOCALS; i++) {
                    if (!locals[i]) {
                        firstFreeLocal = (short) i;
                        if (localsMax < firstFreeLocal)
                            localsMax = firstFreeLocal;
                        return result;
                    }
                }
            }
            else {
                return result;
            }
        }
        throw Context.reportRuntimeError("Program too complex " +
                                         "(out of locals)");
    }

    private short reserveWordLocal(int local)
    {
        if (getNewWordLocal() != local)
            throw new RuntimeException("Local allocation error");
        return (short) local;
    }

    private short getNewWordLocal()
    {
        short result = firstFreeLocal;
        locals[result] = true;
        for (int i = firstFreeLocal + 1; i < MAX_LOCALS; i++) {
            if (!locals[i]) {
                firstFreeLocal = (short) i;
                if (localsMax < firstFreeLocal)
                    localsMax = firstFreeLocal;
                return result;
            }
        }
        throw Context.reportRuntimeError("Program too complex " +
                                         "(out of locals)");
    }

    private void releaseWordpairLocal(short local)
    {
        if (local < firstFreeLocal)
            firstFreeLocal = local;
        locals[local] = false;
        locals[local + 1] = false;
    }

    private void releaseWordLocal(short local)
    {
        if (local < firstFreeLocal)
            firstFreeLocal = local;
        locals[local] = false;
    }

    private void push(int i)
    {
        if ((byte)i == i) {
            if (i == -1) {
                addByteCode(ByteCode.ICONST_M1);
            } else if (0 <= i && i <= 5) {
                addByteCode((byte) (ByteCode.ICONST_0 + i));
            } else {
                addByteCode(ByteCode.BIPUSH, (byte) i);
            }
        } else if ((short)i == i) {
            addByteCode(ByteCode.SIPUSH, (short) i);
        } else {
            classFile.addLoadConstant(i);
        }
    }

    private void push(double d)
    {
        if (d == 0.0) {
            addByteCode(ByteCode.DCONST_0);
        } else if (d == 1.0) {
            addByteCode(ByteCode.DCONST_1);
        /* XXX this breaks all sorts of simple math.
        } else if (Float.MIN_VALUE <= d && d <= Float.MAX_VALUE) {
        loadWordConstant(classFile.addFloatConstant((float) d));
        */
        } else {
            classFile.addLoadConstant((double)d);
        }
    }

    private void pushAsWrapperObject(double num)
    {
        // Generate code to create the new numeric constant
        //
        // new java/lang/<WrapperType>
        // dup
        // push <number>
        // invokestatic java/lang/<WrapperType>/<init>(X)V

        String wrapperType;
        String signature;
        boolean isInteger;
        int inum = (int)num;
        if (inum == num) {
            isInteger = true;
            if ((byte)inum == inum) {
                wrapperType = "java/lang/Byte";
                signature = "(B)V";
            } else if ((short)inum == inum) {
                wrapperType = "java/lang/Short";
                signature = "(S)V";
            } else {
                wrapperType = "java/lang/Integer";
                signature = "(I)V";
            }
        } else {
            isInteger = false;
            // See comments in push(double)
            //if ((float)num == num) {
            //    wrapperType = "java/lang/Float";
            //    signature = "(F)V";
            //}
            //else {
                wrapperType = "java/lang/Double";
                signature = "(D)V";
            //}
        }

        addByteCode(ByteCode.NEW, wrapperType);
        addByteCode(ByteCode.DUP);
        if (isInteger) { push(inum); }
        else { push(num); }
        addSpecialInvoke(wrapperType, "<init>", signature);
    }

    private void push(String s)
    {
        classFile.addLoadConstant(s);
    }

    private void pushUndefined()
    {
        classFile.add(ByteCode.GETSTATIC, "org/mozilla/javascript/Undefined",
                "instance", "Lorg/mozilla/javascript/Scriptable;");
    }

    private static String classNameToSignature(String className)
    {
        return 'L'+className.replace('.', '/')+';';
    }

    private static String getRegexpFieldName(int i)
    {
        return "_re" + i;
    }

    private static String getDirectTargetFieldName(int i)
    {
        return "_dt" + i;
    }

    private static void badTree()
    {
        throw new RuntimeException("Bad tree in codegen");
    }

    private static final String FUNCTION_SUPER_CLASS_NAME =
                          "org.mozilla.javascript.NativeFunction";
    private static final String SCRIPT_SUPER_CLASS_NAME =
                          "org.mozilla.javascript.NativeScript";

    private static final String MAIN_SCRIPT_FIELD = "masterScript";

    private Codegen mainCodegen;
    private boolean isMainCodegen;
    private OptClassNameHelper nameHelper;
    private ObjToIntMap classNames;
    private ObjArray directCallTargets;

    private String generatedClassName;
    private String generatedClassSignature;
    boolean inFunction;
    boolean inDirectCallFunction;
    private ClassFileWriter classFile;
    private int version;

    private String itsSourceFile;
    private int itsLineNumber;

    private int stackDepth;
    private int stackDepthMax;

    private static final int MAX_LOCALS = 256;
    private boolean[] locals;
    private short firstFreeLocal;
    private short localsMax;

    private double[] itsConstantList;
    private int itsConstantListSize;

    // special known locals. If you add a new local here, be sure
    // to initialize it to -1 in startCodeBodyMethod
    private short variableObjectLocal;
    private short scriptResultLocal;
    private short contextLocal;
    private short argsLocal;
    private short thisObjLocal;
    private short funObjLocal;
    private short itsZeroArgArray;
    private short itsOneArgArray;

    private ScriptOrFnNode scriptOrFn;
    private OptFunctionNode fnCurrent;
    private boolean itsUseDynamicScope;
    private boolean hasVarsInRegs;
    private boolean itsForcedObjectParameters;
    private short itsLocalAllocationBase;
    private OptLocalVariable[] debugVars;
    private int epilogueLabel;
    private int optLevel;
}

