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

/**
 * This class transforms a tree to a lower-level representation for codegen.
 *
 * @see Node
 * @author Norris Boyd
 */

public class NodeTransformer
{
    public NodeTransformer(CompilerEnvirons compilerEnv)
    {
        this.compilerEnv = compilerEnv;
    }

    public final void transform(ScriptOrFnNode tree)
    {
        transformCompilationUnit(tree);
        for (int i = 0; i != tree.getFunctionCount(); ++i) {
            FunctionNode fn = tree.getFunctionNode(i);
            transform(fn);
        }
    }

    private void transformCompilationUnit(ScriptOrFnNode tree)
    {
        loops = new ObjArray();
        loopEnds = new ObjArray();
        inFunction = (tree.getType() == Token.FUNCTION);
        // to save against upchecks if no finally blocks are used.
        hasFinally = false;
        transformCompilationUnit_r(tree, tree);
    }

    private void transformCompilationUnit_r(final ScriptOrFnNode tree,
                                            final Node parent)
    {
        Node node = null;
      siblingLoop:
        for (;;) {
            Node previous = null;
            if (node == null) {
                node = parent.getFirstChild();
            } else {
                previous = node;
                node = node.getNext();
            }
            if (node == null) {
                break;
            }

            int type = node.getType();

          typeswitch:
            switch (type) {

              case Token.LABEL:
              {
                Node.Jump labelNode = (Node.Jump)node;
                String id = labelNode.getLabel();

                // check against duplicate labels...
                for (int i=loops.size()-1; i >= 0; i--) {
                    Node n = (Node) loops.get(i);
                    if (n.getType() == Token.LABEL) {
                        String otherId = ((Node.Jump)n).getLabel();
                        if (id.equals(otherId)) {
                            reportError(
                                Context.getMessage1("msg.dup.label", id),
                                node, tree);
                            break typeswitch;
                        }
                    }
                }

                /* Make a target and put it _after_ the following
                 * node.  And in the LABEL node, so breaks get the
                 * right target.
                 */
                Node.Target breakTarget = new Node.Target();
                Node next = node.getNext();
                while (next != null &&
                       (next.getType() == Token.LABEL ||
                        next.getType() == Token.TARGET))
                    next = next.getNext();
                if (next == null)
                    break;
                parent.addChildAfter(breakTarget, next);
                labelNode.target = breakTarget;
                if (next.getType() == Token.LOOP) {
                    labelNode.setContinue(((Node.Jump)next).getContinue());
                } else if (next.getType() == Token.LOCAL_BLOCK) {
                    // check for "for (in)" loop that is wrapped in local_block
                    Node child = next.getFirstChild();
                    if (child != null && child.getType() == Token.LOOP) {
                        labelNode.setContinue(((Node.Jump)child).getContinue());
                    }
                }

                loops.push(node);
                loopEnds.push(breakTarget);

                break;
              }

              case Token.SWITCH:
              {
                Node.Target breakTarget = new Node.Target();
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

                ((Node.Jump)node).target = breakTarget;
                loops.push(node);
                loopEnds.push(breakTarget);
                node.putProp(Node.CASES_PROP, new ObjArray());
                break;
              }

              case Token.DEFAULT:
              case Token.CASE:
              {
                Node sw = (Node) loops.peek();
                if (type == Token.CASE) {
                    ObjArray cases = (ObjArray) sw.getProp(Node.CASES_PROP);
                    cases.add(node);
                } else {
                    sw.putProp(Node.DEFAULT_PROP, node);
                }
                break;
              }

              case Token.LOOP:
                loops.push(node);
                loopEnds.push(((Node.Jump)node).target);
                break;

              case Token.WITH:
              {
                if (inFunction) {
                    // With statements require an activation object.
                    ((FunctionNode) tree).setRequiresActivation(true);
                }
                loops.push(node);
                Node leave = node.getNext();
                if (leave.getType() != Token.LEAVEWITH) {
                    Kit.codeBug();
                }
                loopEnds.push(leave);
                break;
              }

              case Token.TRY:
              {
                Node.Jump jump = (Node.Jump)node;
                Node finallytarget = jump.getFinally();
                if (finallytarget != null) {
                    hasFinally = true;
                    loops.push(node);
                    loopEnds.push(finallytarget);
                }
                break;
              }

              case Token.TARGET:
              case Token.LEAVEWITH:
                if (!loopEnds.isEmpty() && loopEnds.peek() == node) {
                    loopEnds.pop();
                    loops.pop();
                }
                break;

              case Token.RETURN:
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
                Node child = node.getFirstChild();
                boolean inserted = false;
                for (int i=loops.size()-1; i >= 0; i--) {
                    Node n = (Node) loops.get(i);
                    int elemtype = n.getType();
                    if (elemtype == Token.TRY || elemtype == Token.WITH) {
                        if (!inserted) {
                            inserted = true;
                            if (child != null) {
                                node.setType(Token.POPV);
                                // process children now as node will be
                                // changed to point to inserted RETURN_POPV
                                transformCompilationUnit_r(tree, node);
                                Node retPopv = new Node(Token.RETURN_POPV);
                                parent.addChildAfter(retPopv, node);
                                previous = node;
                                node = retPopv;
                            }
                        }
                        Node unwind;
                        if (elemtype == Token.TRY) {
                            Node.Jump jsrnode = new Node.Jump(Token.JSR);
                            Node.Target jsrtarget = ((Node.Jump)n).getFinally();
                            jsrnode.target = jsrtarget;
                            unwind = jsrnode;
                        } else {
                            unwind = new Node(Token.LEAVEWITH);
                        }
                        previous = addBeforeCurrent(parent, previous, node,
                                                    unwind);
                    }
                }
                break;
              }

              case Token.BREAK:
              case Token.CONTINUE:
              {
                Node.Jump jump = (Node.Jump)node;
                Node.Jump loop = null;
                String label = jump.getLabel();

                int i;
                for (i=loops.size()-1; i >= 0; i--) {
                    Node n = (Node) loops.get(i);
                    int elemtype = n.getType();
                    if (elemtype == Token.WITH) {
                        Node leave = new Node(Token.LEAVEWITH);
                        previous = addBeforeCurrent(parent, previous, node,
                                                    leave);
                    } else if (elemtype == Token.TRY) {
                        Node.Jump tryNode = (Node.Jump)n;
                        Node.Jump jsrFinally = new Node.Jump(Token.JSR);
                        jsrFinally.target = tryNode.getFinally();
                        previous = addBeforeCurrent(parent, previous, node,
                                                    jsrFinally);
                    } else if (elemtype == Token.LABEL) {
                        if (label != null) {
                            Node.Jump labelNode = (Node.Jump)n;
                            if (label.equals(labelNode.getLabel())) {
                                loop = labelNode;
                                break;
                            }
                        }
                    } else if (elemtype == Token.LOOP) {
                        if (label == null) {
                               // break/continue the nearest loop if has no label
                               loop = (Node.Jump)n;
                               break;
                        }
                    } else if (elemtype == Token.SWITCH) {
                        if (label == null && type == Token.BREAK) {
                               // break the nearest switch if has no label
                               loop = (Node.Jump)n;
                               break;
                        }
                    }
                }
                Node.Target target;
                if (loop == null) {
                    target = null;
                } else if (type == Token.BREAK) {
                    target = loop.target;
                } else {
                    target = loop.getContinue();
                }
                if (loop == null || target == null) {
                    String msg;
                    Object[] messageArgs = null;
                    if (label == null) {
                        // didn't find an appropriate target
                        msg = Context.getMessage0(
                            (type == Token.CONTINUE)
                                ? "msg.continue.outside" : "msg.bad.break");
                    } else if (loop != null) {
                        msg = Context.getMessage0("msg.continue.nonloop");
                    } else {
                        msg = Context.getMessage1("msg.undef.label", label);
                    }
                    reportError(msg, node, tree);
                    break;
                }
                jump.setType(Token.GOTO);
                jump.target = target;
                break;
              }

              case Token.CALL: {
                int callType = getSpecialCallType(tree, node);
                if (callType != Node.NON_SPECIALCALL) {
                    node.putIntProp(Node.SPECIALCALL_PROP, callType);
                }
                visitCall(node, tree);
                break;
              }

              case Token.NEW: {
                int callType = getSpecialCallType(tree, node);
                if (callType != Node.NON_SPECIALCALL) {
                    node.putIntProp(Node.SPECIALCALL_PROP, callType);
                }
                visitNew(node, tree);
                break;
              }

              case Token.DOT:
              {
                Node right = node.getLastChild();
                right.setType(Token.STRING);
                break;
              }

              case Token.EXPRSTMT:
                node.setType(inFunction ? Token.POP : Token.POPV);
                break;

              case Token.VAR:
              {
                Node result = new Node(Token.BLOCK);
                for (Node cursor = node.getFirstChild(); cursor != null;) {
                    // Move cursor to next before createAssignment get chance
                    // to change n.next
                    Node n = cursor;
                    if (n.getType() != Token.NAME) Kit.codeBug();
                    cursor = cursor.getNext();
                    if (!n.hasChildren())
                        continue;
                    Node init = n.getFirstChild();
                    n.removeChild(init);
                    n.setType(Token.BINDNAME);
                    n = new Node(Token.SETNAME, n, init);
                    Node pop = new Node(Token.POP, n, node.getLineno());
                    result.addChildToBack(pop);
                }
                node = replaceCurrent(parent, previous, node, result);
                break;
              }

              case Token.DELPROP:
              case Token.SETNAME:
              {
                if (!inFunction || inWithStatement())
                    break;
                Node bind = node.getFirstChild();
                if (bind == null || bind.getType() != Token.BINDNAME)
                    break;
                String name = bind.getString();
                if (isActivationNeeded(name)) {
                    // use of "arguments" requires an activation object.
                    ((FunctionNode) tree).setRequiresActivation(true);
                }
                if (tree.hasParamOrVar(name)) {
                    if (type == Token.SETNAME) {
                        node.setType(Token.SETVAR);
                        bind.setType(Token.STRING);
                    } else {
                        // Local variables are by definition permanent
                        Node n = new Node(Token.FALSE);
                        node = replaceCurrent(parent, previous, node, n);
                    }
                }
                break;
              }

              case Token.GETPROP:
                if (inFunction) {
                    Node n = node.getFirstChild().getNext();
                    String name = n == null ? "" : n.getString();
                    if (isActivationNeeded(name)
                        || (name.equals("length")
                            && compilerEnv.getLanguageVersion()
                               == Context.VERSION_1_2))
                    {
                        // Use of "arguments" or "length" in 1.2 requires
                        // an activation object.
                        ((FunctionNode) tree).setRequiresActivation(true);
                    }
                }
                break;

              case Token.NAME:
              {
                if (!inFunction || inWithStatement())
                    break;
                String name = node.getString();
                if (isActivationNeeded(name)) {
                    // Use of "arguments" requires an activation object.
                    ((FunctionNode) tree).setRequiresActivation(true);
                }
                if (tree.hasParamOrVar(name)) {
                    node.setType(Token.GETVAR);
                }
                break;
              }
            }

            transformCompilationUnit_r(tree, node);
        }
    }

    protected void visitNew(Node node, ScriptOrFnNode tree) {
    }

    protected void visitCall(Node node, ScriptOrFnNode tree) {
    }

    protected boolean inWithStatement() {
        for (int i=loops.size()-1; i >= 0; i--) {
            Node n = (Node) loops.get(i);
            if (n.getType() == Token.WITH)
                return true;
        }
        return false;
    }

    /**
     * Return true if the node is a call to a function that requires
     * access to the enclosing activation object.
     */
    private static int getSpecialCallType(Node tree, Node node)
    {
        Node left = node.getFirstChild();
        int type = Node.NON_SPECIALCALL;
        if (left.getType() == Token.NAME) {
            String name = left.getString();
            if (name.equals("eval")) {
                type = Node.SPECIALCALL_EVAL;
            } else if (name.equals("With")) {
                type = Node.SPECIALCALL_WITH;
            }
        } else {
            if (left.getType() == Token.GETPROP) {
                String name = left.getLastChild().getString();
                if (name.equals("eval")) {
                    type = Node.SPECIALCALL_EVAL;
                }
            }
        }
        if (type != Node.NON_SPECIALCALL) {
            // Calls to these functions require activation objects.
            if (tree.getType() == Token.FUNCTION)
                ((FunctionNode) tree).setRequiresActivation(true);
        }
        return type;
    }

    private static Node addBeforeCurrent(Node parent, Node previous,
                                         Node current, Node toAdd)
    {
        if (previous == null) {
            if (!(current == parent.getFirstChild())) Kit.codeBug();
            parent.addChildToFront(toAdd);
        } else {
            if (!(current == previous.getNext())) Kit.codeBug();
            parent.addChildAfter(toAdd, previous);
        }
        return toAdd;
    }

    private static Node replaceCurrent(Node parent, Node previous,
                                       Node current, Node replacement)
    {
        if (previous == null) {
            if (!(current == parent.getFirstChild())) Kit.codeBug();
            parent.replaceChild(current, replacement);
        } else if (previous.next == current) {
            // Check cachedPrev.next == current is necessary due to possible
            // tree mutations
            parent.replaceChildAfter(previous, replacement);
        } else {
            parent.replaceChild(current, replacement);
        }
        return replacement;
    }

    private void reportError(String message, Node stmt, ScriptOrFnNode tree)
    {
        int lineno = stmt.getLineno();
        String sourceName = tree.getSourceName();
        compilerEnv.reportSyntaxError(message, sourceName, lineno, null, 0);
    }

    private boolean isActivationNeeded(String name)
    {
        if ("arguments".equals(name))
            return true;
        if (compilerEnv.activationNames != null
            && compilerEnv.activationNames.containsKey(name))
        {
            return true;
        }
        return false;
    }

    private ObjArray loops;
    private ObjArray loopEnds;
    private boolean inFunction;
    private boolean hasFinally;

    private CompilerEnvirons compilerEnv;
}

