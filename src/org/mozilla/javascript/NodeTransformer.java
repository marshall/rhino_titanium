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
 * Roger Lawrence
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
import java.util.Stack;
import java.util.Vector;

/**
 * This class transforms a tree to a lower-level representation for codegen.
 *
 * @see Node
 * @author Norris Boyd
 */

public class NodeTransformer {
    
    /**
     * Return new instance of this class. So that derived classes
     * can override methods of the transformer.
     */
    public NodeTransformer newInstance() {
        return new NodeTransformer();
    }
    
    public IRFactory createIRFactory(TokenStream ts, Scriptable scope) {
        return new IRFactory(ts, scope);
    }

    public Node transform(Node tree, Node enclosing, TokenStream ts,
                          Scriptable scope) 
    {
        loops = new Stack();
        loopEnds = new Stack();
        inFunction = tree.getType() == TokenStream.FUNCTION;
        if (!inFunction) {
            addVariables(tree, getVariableTable(tree));
        }
        irFactory = createIRFactory(ts, scope);

        // to save against upchecks if no finally blocks are used.
        boolean hasFinally = false;

        PreorderNodeIterator iterator = tree.getPreorderIterator();
        Node node;
        while ((node = iterator.nextNode()) != null) {
            int type = node.getType();

          typeswitch:
            switch (type) {

              case TokenStream.FUNCTION:
                if (node == tree) {
                    // Add the variables to variable table, the
                    // parameters were added earlier.
                    VariableTable vars = getVariableTable(tree);
                    addVariables(tree, vars);

                    // Add return to end if needed.
                    Node stmts = node.getLastChild();
                    Node lastStmt = stmts.getLastChild();
                    if (lastStmt == null ||
                        lastStmt.getType() != TokenStream.RETURN)
                    {
                        stmts.addChildToBack(new Node(TokenStream.RETURN));
                    }

                } else {
                    FunctionNode fnNode = (FunctionNode)
                                          node.getProp(Node.FUNCTION_PROP);
                    if (inFunction) {
                        // Functions containing other functions require 
                        //  activation objects 
                        ((FunctionNode) tree).setRequiresActivation(true);

                        // Nested functions must check their 'this' value to
                        //  insure it is not an activation object:
                        //  see 10.1.6 Activation Object
                        fnNode.setCheckThis(true);
                    }
                    addParameters(fnNode);
                    NodeTransformer inner = newInstance();
                    fnNode = (FunctionNode) 
                            inner.transform(fnNode, tree, ts, scope);
                    node.putProp(Node.FUNCTION_PROP, fnNode);
                    Vector fns = (Vector) tree.getProp(Node.FUNCTION_PROP);
                    if (fns == null) {
                        fns = new Vector(7);
                        tree.putProp(Node.FUNCTION_PROP, fns);
                    }
                    fns.addElement(fnNode);
                }
                break;

              case TokenStream.LABEL:
              {
                Node child = node.getFirstChild();
                node.removeChild(child);
                String id = child.getString();

                // check against duplicate labels...
                for (int i=loops.size()-1; i >= 0; i--) {
                    Node n = (Node) loops.elementAt(i);
                    if (n.getType() == TokenStream.LABEL) {
                        String otherId = (String)n.getProp(Node.LABEL_PROP);
                        if (id.equals(otherId)) {
                            String message = Context.getMessage1(
                                "msg.dup.label", id);
                            reportMessage(Context.getContext(), message, node, 
                                          tree, true, scope);
                            break typeswitch;
                        }
                    }
                }

                node.putProp(Node.LABEL_PROP, id);

                /* Make a target and put it _after_ the following
                 * node.  And in the LABEL node, so breaks get the
                 * right target.
                 */
                Node breakTarget = new Node(TokenStream.TARGET);
                Node parent = iterator.getCurrentParent();
                Node next = node.getNextSibling();
                while (next != null &&
                       (next.getType() == TokenStream.LABEL ||
                        next.getType() == TokenStream.TARGET))
                    next = next.getNextSibling();
                if (next == null)
                    break;
                parent.addChildAfter(breakTarget, next);
                node.putProp(Node.BREAK_PROP, breakTarget);
                
                if (next.getType() == TokenStream.LOOP) {
                    node.putProp(Node.CONTINUE_PROP, 
                                 next.getProp(Node.CONTINUE_PROP));
                }   

                loops.push(node);
                loopEnds.push(breakTarget);

                break;
              }

              case TokenStream.SWITCH:
              {
                Node breakTarget = new Node(TokenStream.TARGET);
                Node parent = iterator.getCurrentParent();
                parent.addChildAfter(breakTarget, node);

                // make all children siblings except for selector
                Node sib = node;
                Node child = node.getFirstChild().next;
                while (child != null) {
                    Node next = child.next;
                    node.removeChild(child);
                    parent.addChildAfter(child, sib);
                    sib = child;
                    child = next;
                }

                node.putProp(Node.BREAK_PROP, breakTarget);
                loops.push(node);
                loopEnds.push(breakTarget);
                node.putProp(Node.CASES_PROP, new Vector(13));
                break;
              }

              case TokenStream.DEFAULT:
              case TokenStream.CASE:
              {
                Node sw = (Node) loops.peek();
                if (type == TokenStream.CASE) {
                    Vector cases = (Vector) sw.getProp(Node.CASES_PROP);
                    cases.addElement(node);
                } else {
                    sw.putProp(Node.DEFAULT_PROP, node);
                }
                break;
              }

              case TokenStream.NEWLOCAL : {
                    Integer localCount
                            = (Integer)(tree.getProp(Node.LOCALCOUNT_PROP));
                    if (localCount == null) {
                        tree.putProp(Node.LOCALCOUNT_PROP, new Integer(1));
                    }
                    else {
                        tree.putProp(Node.LOCALCOUNT_PROP,
                                        new Integer(localCount.intValue() + 1));
                    }
                }
                break;

              case TokenStream.LOOP:
                loops.push(node);
                loopEnds.push(node.getProp(Node.BREAK_PROP));
                break;

              case TokenStream.WITH:
              {
                if (inFunction) {
                    // With statements require an activation object.
                    ((FunctionNode) tree).setRequiresActivation(true);
                }
                loops.push(node);
                Node leave = node.getNextSibling();
                if (leave.getType() != TokenStream.LEAVEWITH) {
                    throw new RuntimeException("Unexpected tree");
                }
                loopEnds.push(leave);
                break;
              }

              case TokenStream.TRY:
              {
                Node finallytarget = (Node)node.getProp(Node.FINALLY_PROP);
                if (finallytarget != null) {
                    hasFinally = true;
                    loops.push(node);
                    loopEnds.push(finallytarget);
                }
                Integer localCount
                        = (Integer)(tree.getProp(Node.LOCALCOUNT_PROP));
                if (localCount == null) {
                    tree.putProp(Node.LOCALCOUNT_PROP, new Integer(1));
                }
                else {
                    tree.putProp(Node.LOCALCOUNT_PROP,
                                 new Integer(localCount.intValue() + 1));
                }
                break;
              }

              case TokenStream.TARGET:
              case TokenStream.LEAVEWITH:
                if (!loopEnds.empty() && loopEnds.peek() == node) {
                    loopEnds.pop();
                    loops.pop();
                }
                break;

              case TokenStream.RETURN:
              {
                /* If we didn't support try/finally, it wouldn't be
                 * necessary to put LEAVEWITH nodes here... but as
                 * we do need a series of JSR FINALLY nodes before
                 * each RETURN, we need to ensure that each finally
                 * block gets the correct scope... which could mean
                 * that some LEAVEWITH nodes are necessary.
                 */
                if (!hasFinally)
                    break;     // skip the whole mess.

                Node parent = iterator.getCurrentParent();
                for (int i=loops.size()-1; i >= 0; i--) {
                    Node n = (Node) loops.elementAt(i);
                    int elemtype = n.getType();
                    if (elemtype == TokenStream.TRY) {
                        Node jsrnode = new Node(TokenStream.JSR);
                        Object jsrtarget = n.getProp(Node.FINALLY_PROP);
                        jsrnode.putProp(Node.TARGET_PROP, jsrtarget);
                        parent.addChildBefore(jsrnode, node);
                    } else if (elemtype == TokenStream.WITH) {
                        parent.addChildBefore(new Node(TokenStream.LEAVEWITH),
                                              node);
                    }
                }
                break;
              }

              case TokenStream.BREAK:
              case TokenStream.CONTINUE:
              {
                Node loop = null;
                boolean labelled = node.hasChildren();
                String id = null;
                if (labelled) {
                    /* get the label */
                    Node child = node.getFirstChild();
                    id = child.getString();
                    node.removeChild(child);
                }

                int i;
                Node parent = iterator.getCurrentParent();
                for (i=loops.size()-1; i >= 0; i--) {
                    Node n = (Node) loops.elementAt(i);
                    int elemtype = n.getType();
                    if (elemtype == TokenStream.WITH) {
                        parent.addChildBefore(new Node(TokenStream.LEAVEWITH),
                                              node);
                    } else if (elemtype == TokenStream.TRY) {
                        Node jsrFinally = new Node(TokenStream.JSR);
                        Object jsrTarget = n.getProp(Node.FINALLY_PROP);
                        jsrFinally.putProp(Node.TARGET_PROP, jsrTarget);
                        parent.addChildBefore(jsrFinally, node);
                    } else if (!labelled &&
                               (elemtype == TokenStream.LOOP ||
                                (elemtype == TokenStream.SWITCH &&
                                 type == TokenStream.BREAK)))
                    {
                        /* if it's a simple break/continue, break from the
                         * nearest enclosing loop or switch
                         */
                        loop = n;
                        break;
                    } else if (labelled &&
                               elemtype == TokenStream.LABEL &&
                               id.equals((String)n.getProp(Node.LABEL_PROP)))
                    {
                        loop = n;
                        break;
                    }
                }
                int propType = type == TokenStream.BREAK
                               ? Node.BREAK_PROP
                               : Node.CONTINUE_PROP;
                Node target = loop == null 
                              ? null
                              : (Node) loop.getProp(propType);
                if (loop == null || target == null) {
                    String message;
                    if (!labelled) {
                        // didn't find an appropriate target
                        if (type == TokenStream.CONTINUE) {
                            message = Context.getMessage
                                ("msg.continue.outside", null);
                        } else {
                            message = Context.getMessage
                                ("msg.bad.break", null);
                        }
                    } else if (loop != null) {
                        message = Context.getMessage0("msg.continue.nonloop");
                    } else {
                        Object[] errArgs = { id };
                        message = Context.getMessage
                            ("msg.undef.label", errArgs);
                    }
                    reportMessage(Context.getContext(), message, node, 
                                  tree, true, scope);
                    node.setType(TokenStream.NOP);
                    break;
                }
                node.setType(TokenStream.GOTO);
                node.putProp(Node.TARGET_PROP, target);
                break;
              }

              case TokenStream.CALL:
                if (isSpecialCallName(tree, node))
                    node.putProp(Node.SPECIALCALL_PROP, Boolean.TRUE);
                visitCall(node, tree);
                break;

              case TokenStream.NEW:
                if (isSpecialCallName(tree, node))
                    node.putProp(Node.SPECIALCALL_PROP, Boolean.TRUE);
                visitNew(node, tree);
                break;

              case TokenStream.DOT:
              {
                Node right = node.getLastChild();
                right.setType(TokenStream.STRING);
                break;
              }

              case TokenStream.EXPRSTMT:
                node.setType(inFunction ? TokenStream.POP : TokenStream.POPV);
                break;

              case TokenStream.OBJECT:
              {
                Vector regexps = (Vector) tree.getProp(Node.REGEXP_PROP);
                if (regexps == null) {
                    regexps = new Vector(3);
                    tree.putProp(Node.REGEXP_PROP, regexps);
                }
                regexps.addElement(node);
                Node n = new Node(TokenStream.OBJECT);
                iterator.replaceCurrent(n);
                n.putProp(Node.REGEXP_PROP, node);
                break;
              }

              case TokenStream.VAR:
              {
                ShallowNodeIterator i = node.getChildIterator();
                Node result = new Node(TokenStream.BLOCK);
                while (i.hasMoreElements()) {
                    Node n = i.nextNode();
                    if (!n.hasChildren())
                        continue;
                    Node init = n.getFirstChild();
                    n.removeChild(init);
                    Node asn = (Node) irFactory.createAssignment(
                                        TokenStream.NOP, n, init, null,
                                        false);
                    Node pop = new Node(TokenStream.POP, asn, node.getDatum());
                    result.addChildToBack(pop);
                }
                iterator.replaceCurrent(result);
                break;
              }

              case TokenStream.DELPROP:
              case TokenStream.SETNAME:
              {
                if (!inFunction || inWithStatement())
                    break;
                Node bind = node.getFirstChild();
                if (bind == null || bind.getType() != TokenStream.BINDNAME)
                    break;
                String name = bind.getString();
                Context cx = Context.getCurrentContext();
                if (cx != null && cx.isActivationNeeded(name)) {
                    // use of "arguments" requires an activation object.
                    ((FunctionNode) tree).setRequiresActivation(true);
                }
                VariableTable vars = getVariableTable(tree);
                if (vars.getVariable(name) != null) {
                    if (type == TokenStream.SETNAME) {
                        node.setType(TokenStream.SETVAR);
                        bind.setType(TokenStream.STRING);
                    } else {
                        // Local variables are by definition permanent
                        Node n = new Node(TokenStream.PRIMARY,
                                          new Integer(TokenStream.FALSE));
                        iterator.replaceCurrent(n);
                    }
                }
                break;
              }
              
              case TokenStream.GETPROP:
                if (inFunction) {
                    Node n = node.getFirstChild().getNextSibling();
                    String name = n == null ? "" : n.getString();
                    Context cx = Context.getCurrentContext();
                    if ((cx != null && cx.isActivationNeeded(name)) ||
                        (name.equals("length") && 
                         Context.getContext().getLanguageVersion() == 
                         Context.VERSION_1_2))
                    {
                        // Use of "arguments" or "length" in 1.2 requires 
                        // an activation object.
                        ((FunctionNode) tree).setRequiresActivation(true);
                    }
                }
                break;

              case TokenStream.NAME:
              {
                if (!inFunction || inWithStatement())
                    break;
                String name = node.getString();
                Context cx = Context.getCurrentContext();
                if (cx != null && cx.isActivationNeeded(name)) {
                    // Use of "arguments" requires an activation object.
                    ((FunctionNode) tree).setRequiresActivation(true);
                }
                VariableTable vars = getVariableTable(tree);
                if (vars.getVariable(name) != null) {
                    node.setType(TokenStream.GETVAR);
                }
                break;
              }
            }
        }

        return tree;
    }

    protected void addVariables(Node tree, VariableTable vars) {
        // OPT: a whole pass to collect variables seems expensive.
        // Could special case to go into statements only.
        boolean inFunction = tree.getType() == TokenStream.FUNCTION;
        PreorderNodeIterator iterator = tree.getPreorderIterator();
        Hashtable ht = null;
        Node node;
        while ((node = iterator.nextNode()) != null) {
            int nodeType = node.getType();
            if (inFunction && nodeType == TokenStream.FUNCTION &&
                node != tree && 
                ((FunctionNode) node.getProp(Node.FUNCTION_PROP)).getFunctionType() == 
                    FunctionNode.FUNCTION_EXPRESSION_STATEMENT) 
            {
                // In a function with both "var x" and "function x",
                // disregard the var statement, independent of order.
                String name = node.getString();
                if (name == null)
                    continue;
                vars.removeLocal(name);
                if (ht == null)
                    ht = new Hashtable();
                ht.put(name, Boolean.TRUE);
            }
            if (nodeType != TokenStream.VAR)
                continue;
            ShallowNodeIterator i = node.getChildIterator();
            while (i.hasMoreElements()) {
                Node n = i.nextNode();
                if (ht == null || ht.get(n.getString()) == null)
                    vars.addLocal(n.getString());
            }
        }
        String name = (String) tree.getDatum();
        if (inFunction && ((FunctionNode) tree).getFunctionType() ==
                          FunctionNode.FUNCTION_EXPRESSION &&
            name != null && name.length() > 0 &&
            vars.getVariable(name) == null)
        {
            // A function expression needs to have its name as a variable
            // (if it isn't already allocated as a variable). See 
            // ECMA Ch. 13.  We add code to the beginning of the function
            // to initialize a local variable of the function's name
            // to the function value.
            vars.addLocal(name);
            Node block = tree.getLastChild();
            Node setFn = new Node(TokenStream.POP,
                            new Node(TokenStream.SETVAR,
                                new Node(TokenStream.STRING, name),
                                new Node(TokenStream.PRIMARY,
                                    new Integer(TokenStream.THISFN))));
            block.addChildrenToFront(setFn);
        }
    }

    protected void addParameters(FunctionNode fnNode) {
        VariableTable vars = fnNode.getVariableTable();
        Node args = fnNode.getFirstChild();
        if (args.getType() == TokenStream.LP && vars.getParameterCount() == 0)
        {
            // Add parameters
            ShallowNodeIterator i = args.getChildIterator();
            while (i.hasMoreElements()) {
                Node n = i.nextNode();
                String arg = n.getString();
                vars.addParameter(arg);
            }
        }
    }
    
    protected void visitNew(Node node, Node tree) {
    }

    protected void visitCall(Node node, Node tree) {
        /*
         * For
         *      Call(GetProp(a, b), c, d)   // or GetElem...
         * we wish to evaluate as
         *      Call(GetProp(tmp=a, b), tmp, c, d)
         *
         * for
         *      Call(Name("a"), b, c)
         * we wish to evaluate as
         *      Call(GetProp(tmp=GetBase("a"), "a"), tmp, b, c)
         *
         * and for
         *      Call(a, b, c);
         * we wish to evaluate as
         *      Call(tmp=a, Parent(tmp), c, d)
         */
        Node left = node.getFirstChild();
        // count the arguments
        int argCount = 0;
        Node arg = left.getNextSibling();
        while (arg != null) {
            arg = arg.getNextSibling();
            argCount++;
        }
        boolean addGetThis = false;
        if (left.getType() == TokenStream.NAME) {
            VariableTable vars = getVariableTable(tree);
            String name = left.getString();
            if (inFunction && vars.getVariable(name) != null && 
                !inWithStatement()) 
            {
                // call to a var. Transform to Call(GetVar("a"), b, c)
                left.setType(TokenStream.GETVAR);
                // fall through to code to add GetParent
            } else {
                // transform to Call(GetProp(GetBase("a"), "a"), b, c)

                node.removeChild(left);
                left.setType(TokenStream.GETBASE);
                Node str = left.cloneNode();
                str.setType(TokenStream.STRING);
                Node getProp = new Node(TokenStream.GETPROP, left, str);
                node.addChildToFront(getProp);
                left = getProp;

                // Conditionally set a flag to add a GETTHIS node.
                // The getThis entry in the runtime will take a
                // Scriptable object intended to be used as a 'this'
                // and make sure that it is neither a With object or
                // an activation object.
                // Executing getThis requires at least two instanceof
                // tests, so we only include it if we are currently
                // inside a 'with' statement, or if we are executing
                // a script (to protect against an eval inside a with).
                addGetThis = inWithStatement() || !inFunction;
                // fall through to GETPROP code
            }
        }
        if (left.getType() != TokenStream.GETPROP &&
            left.getType() != TokenStream.GETELEM)
        {
            node.removeChild(left);
            Node tmp = irFactory.createNewTemp(left);
            Node use = irFactory.createUseTemp(tmp);
            use.putProp(Node.TEMP_PROP, tmp);
            Node parent = new Node(TokenStream.PARENT, use);
            node.addChildToFront(parent);
            node.addChildToFront(tmp);
            return;
        }
        Node leftLeft = left.getFirstChild();
        left.removeChild(leftLeft);
        Node tmp = irFactory.createNewTemp(leftLeft);
        left.addChildToFront(tmp);
        Node use = irFactory.createUseTemp(tmp);
        use.putProp(Node.TEMP_PROP, tmp);
        if (addGetThis)
            use = new Node(TokenStream.GETTHIS, use);
        node.addChildAfter(use, left);
    }

    protected boolean inWithStatement() {
        for (int i=loops.size()-1; i >= 0; i--) {
            Node n = (Node) loops.elementAt(i);
            if (n.getType() == TokenStream.WITH)
                return true;
        }
        return false;
    }

    /**
     * Return true if the node is a call to a function that requires 
     * access to the enclosing activation object.
     */
    private boolean isSpecialCallName(Node tree, Node node) {
        Node left = node.getFirstChild();
        boolean isSpecial = false;
        if (left.getType() == TokenStream.NAME) {
            String name = left.getString();
            isSpecial = name.equals("eval") || name.equals("With");
        } else {
            if (left.getType() == TokenStream.GETPROP) {
                String name = left.getLastChild().getString();
                isSpecial = name.equals("exec");
            }
        }
        if (isSpecial) {
            // Calls to these functions require activation objects.
            if (inFunction)
                ((FunctionNode) tree).setRequiresActivation(true);
            return true;
        }
        return false;
    }

    protected VariableTable createVariableTable() {
        return new VariableTable();
    }

    protected VariableTable getVariableTable(Node tree) {
        if (inFunction) {
            return ((FunctionNode)tree).getVariableTable();
        }
        VariableTable result = (VariableTable)(tree.getProp(Node.VARS_PROP));
        if (result == null) {
            result = createVariableTable();
            tree.putProp(Node.VARS_PROP, result);
        }
        return result;
    }
    
    protected void reportMessage(Context cx, String msg, Node stmt, 
                                 Node tree, boolean isError,
                                 Scriptable scope)
    {
        Object obj = stmt.getDatum();
        int lineno = 0;
        if (obj != null && obj instanceof Integer)
            lineno = ((Integer) obj).intValue();
        Object prop = tree == null 
                      ? null
                      : tree.getProp(Node.SOURCENAME_PROP);
        if (isError) {
            if (scope != null)
            throw NativeGlobal.constructError(
                        cx, "SyntaxError", msg, scope, 
                        (String) prop, lineno, 0, null);
            else
                cx.reportError(msg, (String) prop, lineno, null, 0);
        }
        else
            cx.reportWarning(msg, (String) prop, lineno, null, 0);        
    }

    protected Stack loops;
    protected Stack loopEnds;
    protected boolean inFunction;
    protected IRFactory irFactory;
}

