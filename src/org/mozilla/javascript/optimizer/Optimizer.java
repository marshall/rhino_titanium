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
 * Copyright (C) 1997-1999 Netscape Communications Corporation. All
 * Rights Reserved.
 * 
 * Contributor(s):
 * Norris Boyd
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



package org.mozilla.javascript.optimizer;

import org.mozilla.javascript.*;

import java.io.PrintWriter;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.io.IOException;

import java.util.Vector;
import java.util.Hashtable;

public class Optimizer {

    public Optimizer()
    {
    }

    private static final boolean DEBUG_OPTIMIZER = false;
    private static final boolean DO_CONSTANT_FOLDING = true;

    static int blockCount = 0;

    boolean inDirectCallFunction;
    boolean parameterUsedInNumberContext;

    void optimizeFunction(OptFunctionNode theFunction)
    {
        if (theFunction.requiresActivation()) return;

        inDirectCallFunction = theFunction.isTargetOfDirectCall();

        Node[] theStatementNodes = buildStatementList(theFunction);
        Block[] theBlocks = Block.buildBlocks(theStatementNodes);
        PrintWriter pw = null;
        try {
            if (DEBUG_OPTIMIZER) {
                pw = new PrintWriter(
                            new DataOutputStream(
                                new FileOutputStream(
                                    new File("blocks"
                                                + blockCount++ + ".txt"))));
                pw.println(Block.toString(theBlocks, theStatementNodes));
            }

            OptVariableTable vars = (OptVariableTable) 
                theFunction.getVariableTable();
            if (vars != null) {

                vars.establishIndices();
                for (int i = 0; i < theStatementNodes.length; i++)
                    replaceVariableAccess(theStatementNodes[i], vars);

                if(DO_CONSTANT_FOLDING){
                    foldConstants(theFunction, null);
                }

                reachingDefDataFlow(vars, theBlocks);
                typeFlow(vars, theBlocks);
                findSinglyTypedVars(vars, theBlocks);
                localCSE(theBlocks, theFunction);
                if (!theFunction.requiresActivation()) {
                    /*
                     * Now that we know which local vars are in fact always 
                     * Numbers, we re-write the tree to take advantage of 
                     * that. Any arithmetic or assignment op involving just 
                     * Number typed vars is marked so that the codegen will 
                     * generate non-object code.
                     */
                    parameterUsedInNumberContext = false;
                    for (int i = 0; i < theStatementNodes.length; i++) {
                        rewriteForNumberVariables(theStatementNodes[i]);
                    }
                    theFunction.setParameterNumberContext(parameterUsedInNumberContext);
                    //System.out.println("Function " + theFunction.getFunctionName() + " has parameters in number contexts  : " + parameterUsedInNumberContext);
                }
                if (DEBUG_OPTIMIZER) {
                    for (int i = 0; i < theBlocks.length; i++) {
                        pw.println("For block " + theBlocks[i].getBlockID());
                        theBlocks[i].printLiveOnEntrySet(pw, vars);
                    }
                    vars.print(pw);
                }

            }
            if (DEBUG_OPTIMIZER) pw.close();
        }
        catch (IOException x)   // for the DEBUG_OPTIMIZER i/o
        {
        }
        finally {
            if (DEBUG_OPTIMIZER) pw.close();
        }
    }

    public void optimize(Node tree, int optLevel)
    {
        itsOptLevel = optLevel;
        //  run on one function at a time for now
        PreorderNodeIterator iterator = tree.getPreorderIterator();
        Node node;
        while ((node = iterator.nextNode()) != null) {
                                // should be able to do this more cheaply ?
                               // - run through initial block children ?
            if (node.getType() == TokenStream.FUNCTION) {
                OptFunctionNode theFunction = (OptFunctionNode)
                    node.getProp(Node.FUNCTION_PROP);
                if (theFunction != null)
                    optimizeFunction(theFunction);
            }
        }
    }

    void findSinglyTypedVars(VariableTable theVariables, Block theBlocks[])
    {
/*
    discover the type events for each non-volatile variable (not live
    across function calls). A type event is a def, which sets the target
    type to the source type.
*/
        if (false) {
            /*
                it's enough to prove that every def point for a local variable
                confers the same type on that variable. If that is the case (and
                that type is 'Number') then we can assign that local variable to
                a Double jReg for the life of the function.
            */
            for (int i = 0; i < theBlocks.length; i++) {
                theBlocks[i].findDefs();
            }
        }
        for (int i = 0; i < theVariables.size(); i++) {
            OptLocalVariable lVar = (OptLocalVariable) theVariables.getVariable(i);
            if (!lVar.isParameter()) {
                int theType = lVar.getTypeUnion();
                if (theType == TypeEvent.NumberType) {
                    lVar.setIsNumber();
                }
            }
        }
    }

    void doBlockLocalCSE(Block theBlocks[], Block b,
                    Hashtable theCSETable, boolean beenThere[],
                    OptFunctionNode theFunction)
    {
        if (!beenThere[b.getBlockID()]) {
            beenThere[b.getBlockID()] = true;
            theCSETable = b.localCSE(theCSETable, theFunction);
            Block succ[] = theBlocks[b.getBlockID()].getSuccessorList();
            if (succ != null) {
                for (int i = 0; i < succ.length; i++) {
                    int index = succ[i].getBlockID();
                    Block pred[] = theBlocks[index].getPredecessorList();
                    if (pred.length == 1)
                        doBlockLocalCSE(theBlocks, succ[i],
                                   (Hashtable)(theCSETable.clone()),
                                                beenThere, theFunction);
                }
            }
        }
    }

    void localCSE(Block theBlocks[], OptFunctionNode theFunction)
    {
        boolean beenThere[] = new boolean[theBlocks.length];
        doBlockLocalCSE(theBlocks, theBlocks[0], null, beenThere, theFunction);
        for (int i = 0; i < theBlocks.length; i++) {
            if (!beenThere[i]) theBlocks[i].localCSE(null, theFunction);
        }
    }

    void typeFlow(VariableTable theVariables, Block theBlocks[])
    {
        boolean visit[] = new boolean[theBlocks.length];
        boolean doneOnce[] = new boolean[theBlocks.length];
        int vIndex = 0;
        boolean needRescan = false;
        visit[vIndex] = true;
        while (true) {
            if (visit[vIndex] || !doneOnce[vIndex]) {
                doneOnce[vIndex] = true;
                visit[vIndex] = false;
                if (theBlocks[vIndex].doTypeFlow()) {
                    Block succ[] = theBlocks[vIndex].getSuccessorList();
                    if (succ != null) {
                        for (int i = 0; i < succ.length; i++) {
                            int index = succ[i].getBlockID();
                            visit[index] = true;
                            needRescan |= (index < vIndex);
                        }
                    }
                }
            }
            if (vIndex == (theBlocks.length - 1)) {
                if (needRescan) {
                    vIndex = 0;
                    needRescan = false;
                }
                else
                    break;
            }
            else
                vIndex++;
        }
    }

    void reachingDefDataFlow(VariableTable theVariables, Block theBlocks[])
    {
/*
    initialize the liveOnEntry and liveOnExit sets, then discover the variables
    that are def'd by each function, and those that are used before being def'd
    (hence liveOnEntry)
*/
        for (int i = 0; i < theBlocks.length; i++) {
            theBlocks[i].initLiveOnEntrySets(theVariables);
        }
/*
    this visits every block starting at the last, re-adding the predecessors of
    any block whose inputs change as a result of the dataflow.
    REMIND, better would be to visit in CFG postorder
*/
        boolean visit[] = new boolean[theBlocks.length];
        boolean doneOnce[] = new boolean[theBlocks.length];
        int vIndex = theBlocks.length - 1;
        boolean needRescan = false;
        visit[vIndex] = true;
        while (true) {
            if (visit[vIndex] || !doneOnce[vIndex]) {
                doneOnce[vIndex] = true;
                visit[vIndex] = false;
                if (theBlocks[vIndex].doReachedUseDataFlow()) {
                    Block pred[] = theBlocks[vIndex].getPredecessorList();
                    if (pred != null) {
                        for (int i = 0; i < pred.length; i++) {
                            int index = pred[i].getBlockID();
                            visit[index] = true;
                            needRescan |= (index > vIndex);
                        }
                    }
                }
            }
            if (vIndex == 0) {
                if (needRescan) {
                    vIndex = theBlocks.length - 1;
                    needRescan = false;
                }
                else
                    break;
            }
            else
                vIndex--;
        }
/*
    The liveOnEntry, liveOnExit sets are now complete. Discover the variables
    that are live across function calls.
*/
/*
        if any variable is live on entry to block 0, we have to mark it as
        not jRegable - since it means that someone is trying to access the
        'undefined'-ness of that variable.
*/

        for (int i = 0; i < theBlocks.length; i++) {
            theBlocks[i].markVolatileVariables(theVariables);
        }
        
        theBlocks[0].markAnyTypeVariables(theVariables);
    }

/*
        Each directCall parameter is passed as a pair of values - an object
        and a double. The value passed depends on the type of value available at
        the call site. If a double is available, the object in java/lang/Void.TYPE
        is passed as the object value, and if an object value is available, then
        0.0 is passed as the double value.

        The receiving routine always tests the object value before proceeding.
        If the parameter is being accessed in a 'Number Context' then the code
        sequence is :
        if ("parameter_objectValue" == java/lang/Void.TYPE)
            ...fine..., use the parameter_doubleValue
        else
            toNumber(parameter_objectValue)

        and if the parameter is being referenced in an Object context, the code is
        if ("parameter_objectValue" == java/lang/Void.TYPE)
            new Double(parameter_doubleValue)
        else
            ...fine..., use the parameter_objectValue

        If the receiving code never uses the doubleValue, it is converted on
        entry to a Double instead.
*/


/*
        We're referencing a node in a Number context (i.e. we'd prefer it
        was a double value). If the node is a parameter in a directCall
        function, mark it as being referenced in this context.
*/
    void markDCPNumberContext(Node n)
    {
        if (inDirectCallFunction && (n.getType() == TokenStream.GETVAR))
        {
            OptLocalVariable theVar
                 = (OptLocalVariable)(n.getProp(Node.VARIABLE_PROP));
            if ((theVar != null) && theVar.isParameter()) {
                parameterUsedInNumberContext = true;
            }
        }
    }

    boolean convertParameter(Node n)
    {
        if (inDirectCallFunction && (n.getType() == TokenStream.GETVAR))
        {
            OptLocalVariable theVar
                 = (OptLocalVariable)(n.getProp(Node.VARIABLE_PROP));
            if ((theVar != null) && theVar.isParameter()) {
                n.removeProp(Node.ISNUMBER_PROP);
                return true;
            }
        }
        return false;
    }

    int rewriteForNumberVariables(Node n)
    {
        switch (n.getType()) {
            case TokenStream.POP : {
                    Node child = n.getFirstChild();
                    int type = rewriteForNumberVariables(child);
                    if (type == TypeEvent.NumberType)
                        n.putIntProp(Node.ISNUMBER_PROP, Node.BOTH);
                     return TypeEvent.NoType;
                }
            case TokenStream.NUMBER :
                n.putIntProp(Node.ISNUMBER_PROP, Node.BOTH);
                return TypeEvent.NumberType;

            case TokenStream.GETVAR : {
                    OptLocalVariable theVar
                         = (OptLocalVariable)(n.getProp(Node.VARIABLE_PROP));
                    if (theVar != null) {
                        if (inDirectCallFunction && theVar.isParameter()) {
                            n.putIntProp(Node.ISNUMBER_PROP, Node.BOTH);
                            return TypeEvent.NumberType;
                        }
                        else
                            if (theVar.isNumber()) {
                                n.putIntProp(Node.ISNUMBER_PROP, Node.BOTH);
                                return TypeEvent.NumberType;
                            }
                    }
                    return TypeEvent.NoType;
                }

            case TokenStream.INC :
            case TokenStream.DEC : {
                    Node child = n.getFirstChild();     // will be a GETVAR or GETPROP
                    if (child.getType() == TokenStream.GETVAR) {
                        OptLocalVariable theVar
                             = (OptLocalVariable)(child.getProp(Node.VARIABLE_PROP));
                        if ((theVar != null) && theVar.isNumber()) {
                            n.putIntProp(Node.ISNUMBER_PROP, Node.BOTH);
                            markDCPNumberContext(child);
                            return TypeEvent.NumberType;
                        }
                        else
                            return TypeEvent.NoType;
                    }
                    else
                        return TypeEvent.NoType;
                }
            case TokenStream.SETVAR : {
                    Node lChild = n.getFirstChild();
                    Node rChild = lChild.getNextSibling();
                    int rType = rewriteForNumberVariables(rChild);
                    OptLocalVariable theVar
                         = (OptLocalVariable)(n.getProp(Node.VARIABLE_PROP));
                    if (inDirectCallFunction && theVar.isParameter()) {
                        if (rType == TypeEvent.NumberType) {
                            if (!convertParameter(rChild)) {
                                n.putIntProp(Node.ISNUMBER_PROP, Node.BOTH);
                                return TypeEvent.NumberType;
                            }
                            markDCPNumberContext(rChild);
                            return TypeEvent.NoType;
                        }
                        else
                            return rType;
                    }
                    else {
                        if ((theVar != null) && theVar.isNumber()) {
                            if (rType != TypeEvent.NumberType) {
                                n.removeChild(rChild);
                                Node newRChild = new Node(TokenStream.CONVERT, rChild);
                                newRChild.putProp(Node.TYPE_PROP, Double.class);
                                n.addChildToBack(newRChild);
                            }
                            n.putIntProp(Node.ISNUMBER_PROP, Node.BOTH);
                            markDCPNumberContext(rChild);
                            return TypeEvent.NumberType;
                        }
                        else {
                            if (rType == TypeEvent.NumberType) {
                                if (!convertParameter(rChild)) {
                                    n.removeChild(rChild);
                                    Node newRChild = new Node(TokenStream.CONVERT, rChild);
                                    newRChild.putProp(Node.TYPE_PROP, Object.class);
                                    n.addChildToBack(newRChild);
                                }
                            }
                            return TypeEvent.NoType;
                        }
                    }
                }
            case TokenStream.RELOP : {
                    Node lChild = n.getFirstChild();
                    Node rChild = lChild.getNextSibling();
                    int lType = rewriteForNumberVariables(lChild);
                    int rType = rewriteForNumberVariables(rChild);
                    markDCPNumberContext(lChild);
                    markDCPNumberContext(rChild);

                    if ((n.getInt() == TokenStream.INSTANCEOF)
                            || (n.getInt() == TokenStream.IN)) {
                        if (lType == TypeEvent.NumberType) {
                            if (!convertParameter(lChild)) {
                                n.removeChild(lChild);
                                Node nuChild = new Node(TokenStream.CONVERT, lChild);
                                nuChild.putProp(Node.TYPE_PROP, Object.class);
                                n.addChildToFront(nuChild);
                            }
                        }
                        if (rType == TypeEvent.NumberType) {
                            if (!convertParameter(rChild)) {
                                n.removeChild(rChild);
                                Node nuChild = new Node(TokenStream.CONVERT, rChild);
                                nuChild.putProp(Node.TYPE_PROP, Object.class);
                                n.addChildToBack(nuChild);
                            }
                        }
                    }
                    else {
                        if (convertParameter(lChild)) {
                            if (convertParameter(rChild)) {
                                return TypeEvent.NoType;
                            }
                            else {
                                if (rType == TypeEvent.NumberType) {
                                    n.putIntProp(Node.ISNUMBER_PROP,
                                                 Node.RIGHT);
                                }
                            }
                        }
                        else {
                            if (convertParameter(rChild)) {
                                if (lType == TypeEvent.NumberType) {
                                    n.putIntProp(Node.ISNUMBER_PROP,
                                                 Node.LEFT);
                                }
                            }
                            else {
                                if (lType == TypeEvent.NumberType) {
                                    if (rType == TypeEvent.NumberType) {
                                        n.putIntProp(Node.ISNUMBER_PROP,
                                                     Node.BOTH);
                                    }
                                    else {
                                        n.putIntProp(Node.ISNUMBER_PROP,
                                                     Node.LEFT);
                                    }
                                }
                                else {
                                    if (rType == TypeEvent.NumberType) {
                                        n.putIntProp(Node.ISNUMBER_PROP,
                                                     Node.RIGHT);
                                    }
                                }
                            }
                        }
                     }
                     // we actually build a boolean value
                    return TypeEvent.NoType;
                }

            case TokenStream.ADD : {
                    Node lChild = n.getFirstChild();
                    Node rChild = lChild.getNextSibling();
                    int lType = rewriteForNumberVariables(lChild);
                    int rType = rewriteForNumberVariables(rChild);


                    if (convertParameter(lChild)) {
                        if (convertParameter(rChild)) {
                            return TypeEvent.NoType;
                        }
                        else {
                            if (rType == TypeEvent.NumberType) {
                                n.putIntProp(Node.ISNUMBER_PROP, Node.RIGHT);
                            }
                        }
                    }
                    else {
                        if (convertParameter(rChild)) {
                            if (lType == TypeEvent.NumberType) {
                                n.putIntProp(Node.ISNUMBER_PROP, Node.LEFT);
                            }
                        }
                        else {
                            if (lType == TypeEvent.NumberType) {
                                if (rType == TypeEvent.NumberType) {
                                    n.putIntProp(Node.ISNUMBER_PROP, Node.BOTH);
                                    return TypeEvent.NumberType;
                                }
                                else {
                                    n.putIntProp(Node.ISNUMBER_PROP, Node.LEFT);
                                }
                            }
                            else {
                                if (rType == TypeEvent.NumberType) {
                                    n.putIntProp(Node.ISNUMBER_PROP,
                                                 Node.RIGHT);
                                }
                            }
                        }
                    }
                    return TypeEvent.NoType;
                }

            case TokenStream.BITXOR :
            case TokenStream.BITOR :
            case TokenStream.BITAND :
            case TokenStream.RSH :
            case TokenStream.LSH :
            case TokenStream.SUB :
            case TokenStream.MUL :
            case TokenStream.DIV :
            case TokenStream.MOD : {
                    Node lChild = n.getFirstChild();
                    Node rChild = lChild.getNextSibling();
                    int lType = rewriteForNumberVariables(lChild);
                    int rType = rewriteForNumberVariables(rChild);
                    markDCPNumberContext(lChild);
                    markDCPNumberContext(rChild);
                    if (lType == TypeEvent.NumberType) {
                        if (rType == TypeEvent.NumberType) {
                            n.putIntProp(Node.ISNUMBER_PROP, Node.BOTH);
                            return TypeEvent.NumberType;
                        }
                        else {
                            if (!convertParameter(rChild)) {
                                n.removeChild(rChild);
                                Node newRChild = new Node(TokenStream.CONVERT, rChild);
                                newRChild.putProp(Node.TYPE_PROP, Double.class);
                                n.addChildToBack(newRChild);
                                n.putIntProp(Node.ISNUMBER_PROP, Node.BOTH);
                            }
                            return TypeEvent.NumberType;
                        }
                    }
                    else {
                        if (rType == TypeEvent.NumberType) {
                            if (!convertParameter(lChild)) {
                                n.removeChild(lChild);
                                Node newLChild = new Node(TokenStream.CONVERT, lChild);
                                newLChild.putProp(Node.TYPE_PROP, Double.class);
                                n.addChildToFront(newLChild);
                                n.putIntProp(Node.ISNUMBER_PROP, Node.BOTH);
                            }
                            return TypeEvent.NumberType;
                        }
                        else {
                            if (!convertParameter(lChild)) {
                                n.removeChild(lChild);
                                Node newLChild = new Node(TokenStream.CONVERT, lChild);
                                newLChild.putProp(Node.TYPE_PROP, Double.class);
                                n.addChildToFront(newLChild);
                            }
                            if (!convertParameter(rChild)) {
                                n.removeChild(rChild);
                                Node newRChild = new Node(TokenStream.CONVERT, rChild);
                                newRChild.putProp(Node.TYPE_PROP, Double.class);
                                n.addChildToBack(newRChild);
                            }
                            n.putIntProp(Node.ISNUMBER_PROP, Node.BOTH);
                            return TypeEvent.NumberType;
                        }
                    }
                }
            case TokenStream.SETELEM : {
                    Node arrayBase = n.getFirstChild();
                    Node arrayIndex = arrayBase.getNextSibling();
                    Node rValue = arrayIndex.getNextSibling();
                    int baseType = rewriteForNumberVariables(arrayBase);
                    if (baseType == TypeEvent.NumberType) {// can never happen ???
                        if (!convertParameter(arrayBase)) {
                            n.removeChild(arrayBase);
                            Node nuChild = new Node(TokenStream.CONVERT, arrayBase);
                            nuChild.putProp(Node.TYPE_PROP, Object.class);
                            n.addChildToFront(nuChild);
                        }
                    }
                    int indexType = rewriteForNumberVariables(arrayIndex);
                    if (indexType == TypeEvent.NumberType) {
                        // setting the ISNUMBER_PROP signals the codegen
                        // to use the scriptRuntime.setElem that takes
                        // a double index
                        n.putIntProp(Node.ISNUMBER_PROP, Node.LEFT);
                        markDCPNumberContext(arrayIndex);
                    }
                    int rValueType = rewriteForNumberVariables(rValue);
                    if (rValueType == TypeEvent.NumberType) {
                        if (!convertParameter(rValue)) {
                            n.removeChild(rValue);
                            Node nuChild = new Node(TokenStream.CONVERT, rValue);
                            nuChild.putProp(Node.TYPE_PROP, Object.class);
                            n.addChildToBack(nuChild);
                        }
                    }
                    return TypeEvent.NoType;
                }
            case TokenStream.GETELEM : {
                    Node arrayBase = n.getFirstChild();
                    Node arrayIndex = arrayBase.getNextSibling();
                    int baseType = rewriteForNumberVariables(arrayBase);
                    if (baseType == TypeEvent.NumberType) {// can never happen ???
                        if (!convertParameter(arrayBase)) {
                            n.removeChild(arrayBase);
                            Node nuChild = new Node(TokenStream.CONVERT, arrayBase);
                            nuChild.putProp(Node.TYPE_PROP, Object.class);
                            n.addChildToFront(nuChild);
                        }
                    }
                    int indexType = rewriteForNumberVariables(arrayIndex);
                    if (indexType == TypeEvent.NumberType) {
                        // setting the ISNUMBER_PROP signals the codegen
                        // to use the scriptRuntime.getElem that takes
                        // a double index
                        n.putIntProp(Node.ISNUMBER_PROP, Node.RIGHT);
                        markDCPNumberContext(arrayIndex);
                    }
                    return TypeEvent.NoType;
                }
            case TokenStream.CALL :
                {
                    FunctionNode target
                            = (FunctionNode)n.getProp(Node.DIRECTCALL_PROP);
                    if (target != null) {
/*
    we leave each child as a Number if it can be. The codegen will
    handle moving the pairs of parameters.
*/
                        Node child = n.getFirstChild(); // the function
                        rewriteForNumberVariables(child);
                        child = child.getNextSibling(); // the 'this' object
                        rewriteForNumberVariables(child);
                        child = child.getNextSibling(); // the first arg
                        while (child != null) {
                            int type = rewriteForNumberVariables(child);
                            if (type == TypeEvent.NumberType) {
                                markDCPNumberContext(child);
                            }
                            child = child.getNextSibling();
                        }
                        return TypeEvent.NoType;
                    }
                    // else fall thru...
                }
            default : {
                    Node child = n.getFirstChild();
                    while (child != null) {
                        Node nextChild = child.getNextSibling();
                        int type = rewriteForNumberVariables(child);
                        if (type == TypeEvent.NumberType) {
                            if (!convertParameter(child)) {
                                n.removeChild(child);
                                Node nuChild = new Node(TokenStream.CONVERT, child);
                                nuChild.putProp(Node.TYPE_PROP, Object.class);
                                if (nextChild == null)
                                    n.addChildToBack(nuChild);
                                else
                                    n.addChildBefore(nuChild, nextChild);
                            }
                        }
                        child = nextChild;
                    }
                    return TypeEvent.NoType;
                }
        }
    }

    /*
        Do constant folding, for integers, bools and strings
        as well as for if() statements.
    */
    void foldConstants(Node n, Node parent){
        Node lChild, rChild=null;           // children

        lChild = n.getFirstChild();
        if(lChild == null){                 // no children -- exit
            return;
        }else{
            rChild = lChild.getNextSibling();

            if(rChild == null){
                foldConstants(lChild, n);   // one child -- recurse
                return;
            }
        }

        /* o.w. two children -- recurse on both first and proceed */
        foldConstants(lChild, n);
        foldConstants(rChild, n);

        /* take care of all the other children */
        Node child = rChild.getNextSibling();
        while (child != null) {
            foldConstants(child, n);
            child = child.getNextSibling();
        }


        /* children can change, so recompute them */
        lChild = n.getFirstChild();
        if(lChild == null){                 // no children -- exit
            return;
        }else{
            rChild = lChild.getNextSibling();

            if(rChild == null){
                return;
            }
        }

        /* at this point n has two children or more */
        int lt = lChild.getType();
        int rt = rChild.getType();
        
        Node replace = null;

        /* two or more children */
        switch (n.getType()) {
            case TokenStream.ADD:
                  // numerical addition and string concatenation
                if(lt == TokenStream.NUMBER && rt == TokenStream.NUMBER) {
                      // num + num
                    replace = new Node(TokenStream.NUMBER,
                        lChild.getDouble() + rChild.getDouble());
                }
                else if (lt == TokenStream.STRING && rt == TokenStream.STRING) {
                      // string + string
                    replace = new Node(TokenStream.STRING,
                        lChild.getString() + rChild.getString());
                }
                else if (lt == TokenStream.STRING && rt == TokenStream.NUMBER) {
                    // string + num
                    replace = new Node(TokenStream.STRING,
                        lChild.getString() +
                        ScriptRuntime.numberToString(rChild.getDouble(), 10));
                }
                else if (lt == TokenStream.NUMBER && rt == TokenStream.STRING) {
                    // num + string 
                    replace = new Node(TokenStream.STRING,
                        ScriptRuntime.numberToString(lChild.getDouble(), 10) +
                        rChild.getString());
                }
                // can't do anything if we don't know  both types - since 
                // 0 + object is supposed to call toString on the object and do
                // string concantenation rather than addition
                break;

            case TokenStream.SUB:
                  // subtraction
                if (lt == TokenStream.NUMBER && rt == TokenStream.NUMBER) {
                    //both numbers
                    replace = new Node(TokenStream.NUMBER,
                        lChild.getDouble() - rChild.getDouble());
                }
                else if (lt == TokenStream.NUMBER && lChild.getDouble() == 0) {
                    // first 0: 0-x -> -x
                    replace = new Node(TokenStream.UNARYOP,
                        rChild, TokenStream.SUB);
                }
                else if (rt == TokenStream.NUMBER && rChild.getDouble() == 0) {
                    //second 0: x - 0 -> +x
                    // can not make simply x because x - 0 must be number
                    replace = new Node(TokenStream.UNARYOP, 
                        lChild, TokenStream.ADD);
                }
                break;

            case TokenStream.MUL:
                  // multiplication
                if (lt == TokenStream.NUMBER && rt == TokenStream.NUMBER) {
                    // both constants -- just multiply
                    replace = new Node(TokenStream.NUMBER,
                        lChild.getDouble() * rChild.getDouble());
                }
                else if (lt == TokenStream.NUMBER && lChild.getDouble() == 1) {
                    // first 1: 1*x -> +x
                    // not simply x to force number convertion
                    replace = new Node(TokenStream.UNARYOP,
                        rChild, TokenStream.ADD);
                }
                else if (rt == TokenStream.NUMBER && rChild.getDouble() == 1) {
                    // second 1: x*1 -> +x
                    // not simply x to force number convertion
                    replace = new Node(TokenStream.UNARYOP,
                        lChild, TokenStream.ADD);
                }
                // can't do x*0: Infinity * 0 gives NaN, not 0
                break;

            case TokenStream.DIV:
                // division
                if (lt == TokenStream.NUMBER && rt == TokenStream.NUMBER) {
                    // both constants -- just divide, trust Java to handle x/0
                    replace = new Node(TokenStream.NUMBER,
                        lChild.getDouble() / rChild.getDouble());
                }
                else if (rt == TokenStream.NUMBER && rChild.getDouble() == 1) {
                    // second 1: x/1 -> +x
                    // not simply x to force number convertion
                    replace = new Node(TokenStream.UNARYOP,
                        lChild, TokenStream.ADD);
                }
                break;

            case TokenStream.AND: {
                int isLDefined = isAlwaysDefinedBoolean(lChild);
                if (isLDefined == ALWAYS_FALSE_BOOLEAN) {
                    // if the first one is false, replace with FALSE
                    if (!IRFactory.hasSideEffects(rChild)) {
                        replace = new Node(TokenStream.PRIMARY,
                            TokenStream.FALSE);
                    }
                }

                int isRDefined = isAlwaysDefinedBoolean(rChild);
                if (isRDefined == ALWAYS_FALSE_BOOLEAN) {
                    // if the second one is false, replace with FALSE
                    if (!IRFactory.hasSideEffects(lChild)) {
                        replace = new Node(TokenStream.PRIMARY,
                            TokenStream.FALSE);
                    }
                }
                else if (isRDefined == ALWAYS_TRUE_BOOLEAN) {
                    // if second is true, set to first
                    replace = lChild;
                }

                if (isLDefined == ALWAYS_TRUE_BOOLEAN) {
                    // if first is true, set to second
                    replace = rChild;
                }
                break;
            }

            case TokenStream.OR: {
                int isLDefined = isAlwaysDefinedBoolean(lChild);
                if (isLDefined == ALWAYS_TRUE_BOOLEAN) {
                    // if the first one is true, replace with TRUE
                    if (!IRFactory.hasSideEffects(rChild)) {
                        replace = new Node(TokenStream.PRIMARY,
                            TokenStream.TRUE);
                    }
                }

                int isRDefined = isAlwaysDefinedBoolean(rChild);
                if (isRDefined == ALWAYS_TRUE_BOOLEAN) {
                    // if the second one is true, replace with TRUE
                    if (!IRFactory.hasSideEffects(lChild)) {
                        replace = new Node(TokenStream.PRIMARY,
                            TokenStream.TRUE);
                    }
                }
                else if (isRDefined == ALWAYS_FALSE_BOOLEAN) {
                    // if second is false, set to first
                    replace = lChild;
                }

                if (isLDefined == ALWAYS_FALSE_BOOLEAN) {
                    // if first is false, set to second
                    replace = rChild;
                }
                break;
            }

            case TokenStream.BLOCK:
                /* if statement */
                if (lChild.getType() == TokenStream.IFNE) {
                    Node condition = lChild.getFirstChild();
                    int definedBoolean = isAlwaysDefinedBoolean(condition);

                    if (definedBoolean == ALWAYS_FALSE_BOOLEAN) {
                        //if(false) -> replace by the else clause if it exists
                        Node next1 = rChild.getNextSibling();
                        if (next1 != null) {
                            Node next2 = next1.getNextSibling();
                            if (next2 != null) {
                                Node next3 = next2.getNextSibling();
                                if (next3 != null) {
                                    Node elseClause = next3.getFirstChild();
                                    if (elseClause != null) {
                                        replace = elseClause;
                                    }
                                }
                            }
                        }
                    }
                    else if (definedBoolean == ALWAYS_TRUE_BOOLEAN) {
                        if (rChild.getType() == TokenStream.BLOCK) {
                            replace = rChild.getFirstChild();
                        }
                    }
                }
                break;
        }//switch
        
        if (replace != null) {
            parent.replaceChild(n, replace);
        }

    }
    
    private static final int ALWAYS_TRUE_BOOLEAN = 1;
    private static final int ALWAYS_FALSE_BOOLEAN = -1;

    // Check if Node always mean true or false in boolean context
    private static int isAlwaysDefinedBoolean(Node node) {
        int result = 0;
        int type = node.getType();
        if (type == TokenStream.PRIMARY) {
            int id = node.getInt();
            if (id == TokenStream.FALSE || id == TokenStream.NULL 
                || id == TokenStream.UNDEFINED)
            {
                result = ALWAYS_FALSE_BOOLEAN;
            }
            else if (id == TokenStream.TRUE) {
                result = ALWAYS_TRUE_BOOLEAN;
            }
        }
        else if (type == TokenStream.NUMBER) {
            double num = node.getDouble();
            if (num == 0) {
                // Is it neccessary to check for -0.0 here?
                if (1 / num > 0) {
                    result = ALWAYS_FALSE_BOOLEAN;
                }
            }
            else {
                result = ALWAYS_TRUE_BOOLEAN;
            }
        }
        return result;
    }

    void replaceVariableAccess(Node n, VariableTable theVariables)
    {
        Node child = n.getFirstChild();
        while (child != null) {
            replaceVariableAccess(child, theVariables);
            child = child.getNextSibling();
        }
        switch (n.getType()) {
            case TokenStream.SETVAR : {
                    String name = n.getFirstChild().getString();
                    OptLocalVariable theVar = (OptLocalVariable) 
                        theVariables.getVariable(name);
                    if (theVar != null)
                        n.putProp(Node.VARIABLE_PROP, theVar);
                }
                break;
            case TokenStream.GETVAR : {
                    String name = n.getString();
                    OptLocalVariable theVar = (OptLocalVariable) 
                        theVariables.getVariable(name);
                    if (theVar != null)
                        n.putProp(Node.VARIABLE_PROP, theVar);
                }
                break;
            default :
                break;
        }
    }

    private Node[] buildStatementList(FunctionNode theFunction)
    {
        Vector nodeList = new Vector();

        StmtNodeIterator iterator = new StmtNodeIterator(theFunction);
        Node node = iterator.nextNode();
        while (node != null) {
            nodeList.addElement(node);
            node = iterator.nextNode();
        }
        Node[] result = new Node[nodeList.size()];
        for (int i = 0; i < nodeList.size(); i++) {
            result[i] = (Node)(nodeList.elementAt(i));
        }
        return result;
    }

    int itsOptLevel;
}
