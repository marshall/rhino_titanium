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
 * This class implements the root of the intermediate representation.
 *
 * @author Norris Boyd
 * @author Mike McCabe
 */

public class Node
{
    private static class NumberNode extends Node
    {
        NumberNode(double number)
        {
            super(Token.NUMBER);
            this.number = number;
        }

        double number;
    }

    private static class StringNode extends Node
    {
        StringNode(int type, String str) {
            super(type);
            this.str = str;
        }

        String str;
    }

    public static class Jump extends Node
    {
        public Jump(int type)
        {
            super(type);
        }

        Jump(int type, int lineno)
        {
            super(type, lineno);
        }

        Jump(int type, Node child)
        {
            super(type, child);
        }

        Jump(int type, Node child, int lineno)
        {
            super(type, child, lineno);
        }

        public final String getLabel()
        {
            if (!(type == Token.BREAK || type == Token.CONTINUE
                  || type == Token.LABEL))
            {
                Kit.codeBug();
            }
            return label;
        }

        public final void setLabel(String label)
        {
            if (!(type == Token.BREAK || type == Token.CONTINUE
                  || type == Token.LABEL))
            {
                Kit.codeBug();
            }
            if (label == null) Kit.codeBug();
            if (this.label != null) Kit.codeBug(); //only once
            this.label = label;
        }

        public final Target getFinally()
        {
            if (!(type == Token.TRY)) Kit.codeBug();
            return target2;
        }

        public final void setFinally(Target finallyTarget)
        {
            if (!(type == Token.TRY)) Kit.codeBug();
            if (finallyTarget == null) Kit.codeBug();
            if (target2 != null) Kit.codeBug(); //only once
            target2 = finallyTarget;
        }

        public final Target getContinue()
        {
            if (!(type == Token.LABEL || type == Token.LOOP)) Kit.codeBug();                return target2;
        }

        public final void setContinue(Target continueTarget)
        {
            if (!(type == Token.LABEL || type == Token.LOOP)) Kit.codeBug();                if (continueTarget == null) Kit.codeBug();
            if (target2 != null) Kit.codeBug(); //only once
            target2 = continueTarget;
        }

        public Target target;
        private Target target2;
        private String label;
    }

    public static class Target extends Node
    {
        public Target()
        {
            super(Token.TARGET);
        }

        public int labelId = -1;
    }

    private static class PropListItem
    {
        PropListItem next;
        int type;
        int intValue;
        Object objectValue;
    }


    public Node(int nodeType) {
        type = nodeType;
    }

    public Node(int nodeType, Node child) {
        type = nodeType;
        first = last = child;
        child.next = null;
    }

    public Node(int nodeType, Node left, Node right) {
        type = nodeType;
        first = left;
        last = right;
        left.next = right;
        right.next = null;
    }

    public Node(int nodeType, Node left, Node mid, Node right) {
        type = nodeType;
        first = left;
        last = right;
        left.next = mid;
        mid.next = right;
        right.next = null;
    }

    public Node(int nodeType, int line) {
        type = nodeType;
        lineno = line;
    }

    public Node(int nodeType, Node child, int line) {
        this(nodeType, child);
        lineno = line;
    }

    public Node(int nodeType, Node left, Node right, int line) {
        this(nodeType, left, right);
        lineno = line;
    }

    public Node(int nodeType, Node left, Node mid, Node right, int line) {
        this(nodeType, left, mid, right);
        lineno = line;
    }

    public static Node newNumber(double number) {
        return new NumberNode(number);
    }

    public static Node newString(String str) {
        return new StringNode(Token.STRING, str);
    }

    public static Node newString(int type, String str) {
        return new StringNode(type, str);
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public boolean hasChildren() {
        return first != null;
    }

    public Node getFirstChild() {
        return first;
    }

    public Node getLastChild() {
        return last;
    }

    public Node getNext() {
        return next;
    }

    public Node getChildBefore(Node child) {
        if (child == first)
            return null;
        Node n = first;
        while (n.next != child) {
            n = n.next;
            if (n == null)
                throw new RuntimeException("node is not a child");
        }
        return n;
    }

    public Node getLastSibling() {
        Node n = this;
        while (n.next != null) {
            n = n.next;
        }
        return n;
    }

    public void addChildToFront(Node child) {
        child.next = first;
        first = child;
        if (last == null) {
            last = child;
        }
    }

    public void addChildToBack(Node child) {
        child.next = null;
        if (last == null) {
            first = last = child;
            return;
        }
        last.next = child;
        last = child;
    }

    public void addChildrenToFront(Node children) {
        Node lastSib = children.getLastSibling();
        lastSib.next = first;
        first = children;
        if (last == null) {
            last = lastSib;
        }
    }

    public void addChildrenToBack(Node children) {
        if (last != null) {
            last.next = children;
        }
        last = children.getLastSibling();
        if (first == null) {
            first = children;
        }
    }

    /**
     * Add 'child' before 'node'.
     */
    public void addChildBefore(Node newChild, Node node) {
        if (newChild.next != null)
            throw new RuntimeException(
                      "newChild had siblings in addChildBefore");
        if (first == node) {
            newChild.next = first;
            first = newChild;
            return;
        }
        Node prev = getChildBefore(node);
        addChildAfter(newChild, prev);
    }

    /**
     * Add 'child' after 'node'.
     */
    public void addChildAfter(Node newChild, Node node) {
        if (newChild.next != null)
            throw new RuntimeException(
                      "newChild had siblings in addChildAfter");
        newChild.next = node.next;
        node.next = newChild;
        if (last == node)
            last = newChild;
    }

    public void removeChild(Node child) {
        Node prev = getChildBefore(child);
        if (prev == null)
            first = first.next;
        else
            prev.next = child.next;
        if (child == last) last = prev;
        child.next = null;
    }

    public void replaceChild(Node child, Node newChild) {
        newChild.next = child.next;
        if (child == first) {
            first = newChild;
        } else {
            Node prev = getChildBefore(child);
            prev.next = newChild;
        }
        if (child == last)
            last = newChild;
        child.next = null;
    }

    public void replaceChildAfter(Node prevChild, Node newChild) {
        Node child = prevChild.next;
        newChild.next = child.next;
        prevChild.next = newChild;
        if (child == last)
            last = newChild;
        child.next = null;
    }

    public static final int
        FUNCTION_PROP     =  1,
        TEMP_PROP         =  2,
        LOCAL_PROP        =  3,
        LOCAL_BLOCK_PROP  =  4,
        FIXUPS_PROP       =  5,
        USES_PROP         =  6,
        REGEXP_PROP       =  7,
        CASES_PROP        =  8,
        DEFAULT_PROP      =  9,
        CASEARRAY_PROP    = 10,
        SPECIAL_PROP_PROP = 11,
    /*
        the following properties are defined and manipulated by the
        optimizer -
        TARGETBLOCK_PROP - the block referenced by a branch node
        VARIABLE_PROP - the variable referenced by a BIND or NAME node
        LASTUSE_PROP - that variable node is the last reference before
                        a new def or the end of the block
        ISNUMBER_PROP - this node generates code on Number children and
                        delivers a Number result (as opposed to Objects)
        DIRECTCALL_PROP - this call node should emit code to test the function
                          object against the known class and call diret if it
                          matches.
    */

        TARGETBLOCK_PROP  = 12,
        VARIABLE_PROP     = 13,
        LASTUSE_PROP      = 14,
        ISNUMBER_PROP     = 15,
        DIRECTCALL_PROP   = 16,
        SPECIALCALL_PROP  = 17;

    public static final int    // this value of the SPECIAL_PROP_PROP specifies
        SPECIAL_PROP_PROTO  = 1,
        SPECIAL_PROP_PARENT = 2;

    public static final int    // this value of the ISNUMBER_PROP specifies
        BOTH = 0,               // which of the children are Number types
        LEFT = 1,
        RIGHT = 2;

    public static final int    // this value of the SPECIALCALL_PROP specifies
        NON_SPECIALCALL  = 0,
        SPECIALCALL_EVAL = 1,
        SPECIALCALL_WITH = 2;

    private static final String propToString(int propType) {
        if (Token.printTrees) {
            // If Context.printTrees is false, the compiler
            // can remove all these strings.
            switch (propType) {
                case FUNCTION_PROP:      return "function";
                case TEMP_PROP:          return "temp";
                case LOCAL_PROP:         return "local";
                case LOCAL_BLOCK_PROP:   return "local_block";
                case FIXUPS_PROP:        return "fixups";
                case USES_PROP:          return "uses";
                case REGEXP_PROP:        return "regexp";
                case CASES_PROP:         return "cases";
                case DEFAULT_PROP:       return "default";
                case CASEARRAY_PROP:     return "casearray";
                case SPECIAL_PROP_PROP:  return "special_prop";

                case TARGETBLOCK_PROP:   return "targetblock";
                case VARIABLE_PROP:      return "variable";
                case LASTUSE_PROP:       return "lastuse";
                case ISNUMBER_PROP:      return "isnumber";
                case DIRECTCALL_PROP:    return "directcall";

                case SPECIALCALL_PROP:   return "specialcall";

                default: Kit.codeBug();
            }
        }
        return null;
    }

    private PropListItem lookupProperty(int propType)
    {
        PropListItem x = propListHead;
        while (x != null && propType != x.type) {
            x = x.next;
        }
        return x;
    }

    private PropListItem ensureProperty(int propType)
    {
        PropListItem item = lookupProperty(propType);
        if (item == null) {
            item = new PropListItem();
            item.type = propType;
            item.next = propListHead;
            propListHead = item;
        }
        return item;
    }

    public void removeProp(int propType)
    {
        PropListItem x = propListHead;
        if (x != null) {
            PropListItem prev = null;
            while (x.type != propType) {
                prev = x;
                x = x.next;
                if (x == null) { return; }
            }
            if (prev == null) {
                propListHead = x.next;
            } else {
                prev.next = x.next;
            }
        }
    }

    public Object getProp(int propType)
    {
        PropListItem item = lookupProperty(propType);
        if (item == null) { return null; }
        return item.objectValue;
    }

    public int getIntProp(int propType, int defaultValue)
    {
        PropListItem item = lookupProperty(propType);
        if (item == null) { return defaultValue; }
        return item.intValue;
    }

    public int getExistingIntProp(int propType)
    {
        PropListItem item = lookupProperty(propType);
        if (item == null) { Kit.codeBug(); }
        return item.intValue;
    }

    public void putProp(int propType, Object prop)
    {
        if (prop == null) {
            removeProp(propType);
        } else {
            PropListItem item = ensureProperty(propType);
            item.objectValue = prop;
        }
    }

    public void putIntProp(int propType, int prop)
    {
        PropListItem item = ensureProperty(propType);
        item.intValue = prop;
    }

    public int getLineno() {
        return lineno;
    }

    /** Can only be called when <tt>getType() == Token.NUMBER</tt> */
    public final double getDouble() {
        return ((NumberNode)this).number;
    }

    public final void setDouble(double number) {
        ((NumberNode)this).number = number;
    }

    /** Can only be called when node has String context. */
    public final String getString() {
        return ((StringNode)this).str;
    }

    /** Can only be called when node has String context. */
    public final void setString(String s) {
        if (s == null) Kit.codeBug();
        ((StringNode)this).str = s;
    }

    public String toString() {
        if (Token.printTrees) {
            StringBuffer sb = new StringBuffer(Token.name(type));
            if (this instanceof StringNode) {
                sb.append(' ');
                sb.append(getString());
            } else if (this instanceof ScriptOrFnNode) {
                ScriptOrFnNode sof = (ScriptOrFnNode)this;
                if (this instanceof FunctionNode) {
                    FunctionNode fn = (FunctionNode)this;
                    sb.append(' ');
                    sb.append(fn.getFunctionName());
                }
                sb.append(" [source name: ");
                sb.append(sof.getSourceName());
                sb.append("] [encoded source length: ");
                sb.append(sof.getEncodedSourceEnd()
                          - sof.getEncodedSourceStart());
                sb.append("] [base line: ");
                sb.append(sof.getBaseLineno());
                sb.append("] [end line: ");
                sb.append(sof.getEndLineno());
                sb.append(']');
            } else if (this instanceof Jump) {
                Jump jump = (Jump)this;
                if (type == Token.BREAK || type == Token.CONTINUE) {
                    String label = jump.getLabel();
                    if (label != null) {
                        sb.append(" [label: ");
                        sb.append(label);
                        sb.append(']');
                    }
                } else if (type == Token.TRY) {
                    Node catchNode = jump.target;
                    Node.Target finallyTarget = jump.getFinally();
                    if (catchNode != null) {
                        sb.append(" [catch: ");
                        sb.append(catchNode);
                        sb.append(']');
                    }
                    if (finallyTarget != null) {
                        sb.append(" [finally: ");
                        sb.append(finallyTarget);
                        sb.append(']');
                    }
                } else if (type == Token.LABEL || type == Token.LOOP
                           || type == Token.SWITCH)
                {
                    sb.append(" [break: ");
                    sb.append(jump.target);
                    sb.append(']');
                    if (type == Token.LOOP) {
                        sb.append(" [continue: ");
                        sb.append(jump.getContinue());
                        sb.append(']');
                    }
                } else {
                    sb.append(" [target: ");
                    sb.append(jump.target);
                    sb.append(']');
                }
            } else if (this instanceof Target) {
                Target target = (Target)this;
                sb.append(' ');
                sb.append(hashCode());
                sb.append(" [labelId: ");
                sb.append(target.labelId);
                sb.append(']');
            } else if (type == Token.NUMBER) {
                sb.append(' ');
                sb.append(getDouble());
            }
            if (lineno != -1) {
                sb.append(' ');
                sb.append(lineno);
            }

            for (PropListItem x = propListHead; x != null; x = x.next) {
                int type = x.type;
                sb.append(" [");
                sb.append(propToString(type));
                sb.append(": ");
                String value;
                switch (type) {
                  case FIXUPS_PROP : // can't add this as it recurses
                    value = "fixups property";
                    break;
                  case TARGETBLOCK_PROP : // can't add this as it recurses
                    value = "target block property";
                    break;
                  case LASTUSE_PROP :     // can't add this as it is dull
                    value = "last use property";
                    break;
                  case LOCAL_BLOCK_PROP :     // can't add this as it is dull
                    value = "last local block";
                    break;
                  case SPECIAL_PROP_PROP:
                    switch (x.intValue) {
                      case SPECIAL_PROP_PROTO:
                        value = "__proto__";
                        break;
                      case SPECIAL_PROP_PARENT:
                        value = "__parent__";
                        break;
                      default:
                        throw Kit.codeBug();
                    }
                    break;
                  case ISNUMBER_PROP:
                    switch (x.intValue) {
                      case BOTH:
                        value = "both";
                        break;
                      case RIGHT:
                        value = "right";
                        break;
                      case LEFT:
                        value = "left";
                        break;
                      default:
                        throw Kit.codeBug();
                    }
                    break;
                  case SPECIALCALL_PROP:
                    switch (x.intValue) {
                      case SPECIALCALL_EVAL:
                        value = "eval";
                        break;
                      case SPECIALCALL_WITH:
                        value = "with";
                        break;
                      default:
                        // NON_SPECIALCALL should not be stored
                        throw Kit.codeBug();
                    }
                    break;
                  default :
                    Object obj = x.objectValue;
                    if (obj != null) {
                        value = obj.toString();
                    } else {
                        value = String.valueOf(x.intValue);
                    }
                    break;
                }
                sb.append(value);
                sb.append(']');
            }
            return sb.toString();
        }
        return null;
    }

    public String toStringTree(ScriptOrFnNode treeTop) {
        if (Token.printTrees) {
            StringBuffer sb = new StringBuffer();
            toStringTreeHelper(treeTop, this, 0, sb);
            return sb.toString();
        }
        return null;
    }

    private static void toStringTreeHelper(ScriptOrFnNode treeTop, Node n,
                                           int level, StringBuffer sb)
    {
        if (Token.printTrees) {
            for (int i = 0; i != level; ++i) {
                sb.append("    ");
            }
            sb.append(n.toString());
            sb.append('\n');
            for (Node cursor = n.getFirstChild(); cursor != null;
                 cursor = cursor.getNext())
            {
                if (cursor.getType() == Token.FUNCTION) {
                    int fnIndex = cursor.getExistingIntProp(Node.FUNCTION_PROP);
                    FunctionNode fn = treeTop.getFunctionNode(fnIndex);
                    toStringTreeHelper(fn, fn, level + 1, sb);
                } else {
                    toStringTreeHelper(treeTop, cursor, level + 1, sb);
                }
            }
        }
    }

    int type;              // type of the node; Token.NAME for example
    Node next;             // next sibling
    private Node first;    // first element of a linked list of children
    private Node last;     // last element of a linked list of children
    private int lineno = -1;    // encapsulated int data; depends on type

    /**
     * Linked list of properties. Since vast majority of nodes would have
     * no more then 2 properties, linked list saves memory and provides
     * fast lookup. If this does not holds, propListHead can be replaced
     * by UintMap.
     */
    private PropListItem propListHead;
}

