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
 * Mike Ang
 * Igor Bukanov
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

import java.io.Reader;
import java.io.IOException;

/**
 * This class implements the JavaScript parser.
 *
 * It is based on the C source files jsparse.c and jsparse.h
 * in the jsref package.
 *
 * @see TokenStream
 *
 * @author Mike McCabe
 * @author Brendan Eich
 */

public class Parser
{
    public Parser(CompilerEnvirons compilerEnv)
    {
        this.compilerEnv = compilerEnv;
    }

    protected Decompiler createDecompiler(CompilerEnvirons compilerEnv)
    {
        return new Decompiler();
    }


    /*
     * Build a parse tree from the given sourceString.
     *
     * @return an Object representing the parsed
     * program.  If the parse fails, null will be returned.  (The
     * parse failure will result in a call to the ErrorReporter from
     * CompilerEnvirons.)
     */
    public ScriptOrFnNode parse(String sourceString,
                                String sourceLocation, int lineno)
    {
        this.ts = new TokenStream(compilerEnv, null, sourceString,
                                  sourceLocation, lineno);
        try {
            return parse();
        } catch (IOException ex) {
            // Should never happen
            throw new IllegalStateException(ex.getMessage());
        }
    }

    /*
     * Build a parse tree from the given sourceString.
     *
     * @return an Object representing the parsed
     * program.  If the parse fails, null will be returned.  (The
     * parse failure will result in a call to the ErrorReporter from
     * CompilerEnvirons.)
     */
    public ScriptOrFnNode parse(Reader sourceReader,
                                String sourceLocation, int lineno)
        throws IOException
    {
        this.ts = new TokenStream(compilerEnv, sourceReader, null,
                                  sourceLocation, lineno);
        return parse();
    }

    private void mustMatchToken(int toMatch, String messageId)
        throws IOException, ParserException
    {
        int tt;
        if ((tt = ts.getToken()) != toMatch) {
            reportError(messageId);
            ts.ungetToken(tt); // In case the parser decides to continue
        }
    }

    void reportError(String messageId)
    {
        this.ok = false;
        ts.reportCurrentLineError(Context.getMessage0(messageId));

        // Throw a ParserException exception to unwind the recursive descent
        // parse.
        throw new ParserException();
    }

    private ScriptOrFnNode parse()
        throws IOException
    {
        this.decompiler = createDecompiler(compilerEnv);
        this.nf = new IRFactory(this);
        currentScriptOrFn = nf.createScript();
        this.decompiler = decompiler;
        int sourceStartOffset = decompiler.getCurrentOffset();
        this.encodedSource = null;
        decompiler.addToken(Token.SCRIPT);

        this.ok = true;

        int baseLineno = ts.getLineno();  // line number where source starts

        /* so we have something to add nodes to until
         * we've collected all the source */
        Object pn = nf.createLeaf(Token.BLOCK);

        try {
            for (;;) {
                ts.flags |= TokenStream.TSF_REGEXP;
                int tt = ts.getToken();
                ts.flags &= ~TokenStream.TSF_REGEXP;

                if (tt <= Token.EOF) {
                    break;
                }

                Object n;
                if (tt == Token.FUNCTION) {
                    try {
                        n = function(FunctionNode.FUNCTION_STATEMENT);
                    } catch (ParserException e) {
                        this.ok = false;
                        break;
                    }
                } else {
                    ts.ungetToken(tt);
                    n = statement();
                }
                nf.addChildToBack(pn, n);
            }
        } catch (StackOverflowError ex) {
            String msg = Context.getMessage0("mag.too.deep.parser.recursion");
            throw Context.reportRuntimeError(msg, ts.getSourceName(),
                                             ts.getLineno(), null, 0);
        }

        if (!this.ok) {
            // XXX ts.clearPushback() call here?
            return null;
        }

        currentScriptOrFn.setSourceName(ts.getSourceName());
        currentScriptOrFn.setBaseLineno(baseLineno);
        currentScriptOrFn.setEndLineno(ts.getLineno());

        int sourceEndOffset = decompiler.getCurrentOffset();
        currentScriptOrFn.setEncodedSourceBounds(sourceStartOffset,
                                                 sourceEndOffset);

        nf.initScript(currentScriptOrFn, pn);

        if (compilerEnv.isGeneratingSource()) {
            encodedSource = decompiler.getEncodedSource();
        }
        this.decompiler = null; // It helps GC

        return currentScriptOrFn;
    }

    public String getEncodedSource()
    {
        return encodedSource;
    }

    public boolean eof()
    {
        return ts.eof();
    }

    /*
     * The C version of this function takes an argument list,
     * which doesn't seem to be needed for tree generation...
     * it'd only be useful for checking argument hiding, which
     * I'm not doing anyway...
     */
    private Object parseFunctionBody()
        throws IOException
    {
        int oldflags = ts.flags;
        ts.flags &= ~(TokenStream.TSF_RETURN_EXPR
                      | TokenStream.TSF_RETURN_VOID);
        ts.flags |= TokenStream.TSF_FUNCTION;

        Object pn = nf.createBlock(ts.getLineno());
        try {
            int tt;
            while((tt = ts.peekToken()) > Token.EOF && tt != Token.RC) {
                Object n;
                if (tt == Token.FUNCTION) {
                    ts.getToken();
                    n = function(FunctionNode.FUNCTION_STATEMENT);
                } else {
                    n = statement();
                }
                nf.addChildToBack(pn, n);
            }
        } catch (ParserException e) {
            this.ok = false;
        } finally {
            // also in finally block:
            // flushNewLines, clearPushback.

            ts.flags = oldflags;
        }

        return pn;
    }

    private Object function(int functionType)
        throws IOException, ParserException
    {
        int syntheticType = functionType;
        int baseLineno = ts.getLineno();  // line number where source starts

        String name;
        Object memberExprNode = null;
        if (ts.matchToken(Token.NAME)) {
            name = ts.getString();
            if (!ts.matchToken(Token.LP)) {
                if (compilerEnv.allowMemberExprAsFunctionName) {
                    // Extension to ECMA: if 'function <name>' does not follow
                    // by '(', assume <name> starts memberExpr
                    decompiler.addName(name);
                    Object memberExprHead = nf.createName(name);
                    name = "";
                    memberExprNode = memberExprTail(false, memberExprHead);
                }
                mustMatchToken(Token.LP, "msg.no.paren.parms");
            }
        } else if (ts.matchToken(Token.LP)) {
            // Anonymous function
            name = "";
        } else {
            name = "";
            if (compilerEnv.allowMemberExprAsFunctionName) {
                // Note that memberExpr can not start with '(' like
                // in function (1+2).toString(), because 'function (' already
                // processed as anonymous function
                memberExprNode = memberExpr(false);
            }
            mustMatchToken(Token.LP, "msg.no.paren.parms");
        }

        if (memberExprNode != null) {
            syntheticType = FunctionNode.FUNCTION_EXPRESSION;
            // transform 'function' <memberExpr> to  <memberExpr> = function
            // even in the decompilated source
            decompiler.addToken(Token.ASSIGN);
        }

        boolean nested = (currentScriptOrFn.type == Token.FUNCTION);

        FunctionNode fnNode = nf.createFunction(name);
        if (nested) {
            // Nested functions must check their 'this' value to insure
            // it is not an activation object: see 10.1.6 Activation Object
            fnNode.setCheckThis();
        }
        if (nested || nestingOfWith > 0) {
            // 1. Nested functions are not affected by the dynamic scope flag
            // as dynamic scope is already a parent of their scope.
            // 2. Functions defined under the with statement also immune to
            // this setup, in which case dynamic scope is ignored in favor
            // of with object.
            fnNode.setIgnoreDynamicScope();
        }

        int functionIndex = currentScriptOrFn.addFunction(fnNode);

        int functionSourceStart = decompiler.markFunctionStart(syntheticType,
                                                               name);
        int functionSourceEnd;

        ScriptOrFnNode savedScriptOrFn = currentScriptOrFn;
        currentScriptOrFn = fnNode;
        int savedNestingOfWith = nestingOfWith;
        nestingOfWith = 0;

        Object body;
        String source;
        try {
            decompiler.addToken(Token.LP);
            if (!ts.matchToken(Token.RP)) {
                boolean first = true;
                do {
                    if (!first)
                        decompiler.addToken(Token.COMMA);
                    first = false;
                    mustMatchToken(Token.NAME, "msg.no.parm");
                    String s = ts.getString();
                    if (fnNode.hasParamOrVar(s)) {
                        ts.reportCurrentLineWarning(Context.getMessage1(
                            "msg.dup.parms", s));
                    }
                    fnNode.addParam(s);
                    decompiler.addName(s);
                } while (ts.matchToken(Token.COMMA));

                mustMatchToken(Token.RP, "msg.no.paren.after.parms");
            }
            decompiler.addToken(Token.RP);

            mustMatchToken(Token.LC, "msg.no.brace.body");
            decompiler.addEOL(Token.LC);
            body = parseFunctionBody();
            mustMatchToken(Token.RC, "msg.no.brace.after.body");

            decompiler.addToken(Token.RC);
            functionSourceEnd = decompiler.markFunctionEnd(functionSourceStart);
            if (functionType != FunctionNode.FUNCTION_EXPRESSION) {
                checkWellTerminatedFunction();
                if (memberExprNode == null) {
                    // Add EOL only if function is not part of expression
                    // since it gets SEMI + EOL from Statement in that case
                    decompiler.addToken(Token.EOL);
                } else {
                    // Add ';' to make 'function x.f(){}'
                    // and 'x.f = function(){}'
                    // to print the same strings when decompiling
                    decompiler.addEOL(Token.SEMI);
                }
            }
        }
        finally {
            currentScriptOrFn = savedScriptOrFn;
            nestingOfWith = savedNestingOfWith;
        }

        fnNode.setEncodedSourceBounds(functionSourceStart, functionSourceEnd);
        fnNode.setSourceName(ts.getSourceName());
        fnNode.setBaseLineno(baseLineno);
        fnNode.setEndLineno(ts.getLineno());

        Object pn;
        if (memberExprNode == null) {
            pn = nf.initFunction(fnNode, functionIndex, body, syntheticType);
            if (functionType == FunctionNode.FUNCTION_EXPRESSION_STATEMENT) {
                // The following can be removed but then code generators should
                // be modified not to push on the stack function expression
                // statements
                pn = nf.createExprStatementNoReturn(pn, baseLineno);
            }
        } else {
            pn = nf.initFunction(fnNode, functionIndex, body, syntheticType);
            pn = nf.createAssignment(memberExprNode, pn);
            if (functionType != FunctionNode.FUNCTION_EXPRESSION) {
                pn = nf.createExprStatement(pn, baseLineno);
            }
        }
        return pn;
    }

    private Object statements()
        throws IOException
    {
        Object pn = nf.createBlock(ts.getLineno());

        int tt;
        while((tt = ts.peekToken()) > Token.EOF && tt != Token.RC) {
            nf.addChildToBack(pn, statement());
        }

        return pn;
    }

    private Object condition()
        throws IOException, ParserException
    {
        Object pn;
        mustMatchToken(Token.LP, "msg.no.paren.cond");
        decompiler.addToken(Token.LP);
        pn = expr(false);
        mustMatchToken(Token.RP, "msg.no.paren.after.cond");
        decompiler.addToken(Token.RP);

        // there's a check here in jsparse.c that corrects = to ==

        return pn;
    }

    private void checkWellTerminated()
        throws IOException, ParserException
    {
        int tt = ts.peekTokenSameLine();
        switch (tt) {
        case Token.ERROR:
        case Token.EOF:
        case Token.EOL:
        case Token.SEMI:
        case Token.RC:
            return;

        case Token.FUNCTION:
            if (compilerEnv.languageVersion < Context.VERSION_1_2) {
              /*
               * Checking against version < 1.2 and version >= 1.0
               * in the above line breaks old javascript, so we keep it
               * this way for now... XXX warning needed?
               */
                return;
            }
        }
        reportError("msg.no.semi.stmt");
    }

    private void checkWellTerminatedFunction()
        throws IOException, ParserException
    {
        if (compilerEnv.languageVersion < Context.VERSION_1_2) {
            // See comments in checkWellTerminated
             return;
        }
        checkWellTerminated();
    }

    // match a NAME; return null if no match.
    private String matchLabel()
        throws IOException, ParserException
    {
        int lineno = ts.getLineno();

        String label = null;
        int tt;
        tt = ts.peekTokenSameLine();
        if (tt == Token.NAME) {
            ts.getToken();
            label = ts.getString();
        }

        if (lineno == ts.getLineno())
            checkWellTerminated();

        return label;
    }

    private Object statement()
        throws IOException
    {
        try {
            return statementHelper();
        } catch (ParserException e) {
            // skip to end of statement
            int lineno = ts.getLineno();
            int t;
            do {
                t = ts.getToken();
            } while (t != Token.SEMI && t != Token.EOL &&
                     t != Token.EOF && t != Token.ERROR);
            return nf.createExprStatement(nf.createName("error"), lineno);
        }
    }

    /**
     * Whether the "catch (e: e instanceof Exception) { ... }" syntax
     * is implemented.
     */

    private Object statementHelper()
        throws IOException, ParserException
    {
        Object pn = null;

        // If skipsemi == true, don't add SEMI + EOL to source at the
        // end of this statment.  For compound statements, IF/FOR etc.
        boolean skipsemi = false;

        int tt;

        tt = ts.getToken();

        switch(tt) {
        case Token.IF: {
            skipsemi = true;

            decompiler.addToken(Token.IF);
            int lineno = ts.getLineno();
            Object cond = condition();
            decompiler.addEOL(Token.LC);
            Object ifTrue = statement();
            Object ifFalse = null;
            if (ts.matchToken(Token.ELSE)) {
                decompiler.addToken(Token.RC);
                decompiler.addToken(Token.ELSE);
                decompiler.addEOL(Token.LC);
                ifFalse = statement();
            }
            decompiler.addEOL(Token.RC);
            pn = nf.createIf(cond, ifTrue, ifFalse, lineno);
            break;
        }

        case Token.SWITCH: {
            skipsemi = true;

            decompiler.addToken(Token.SWITCH);
            pn = nf.createSwitch(ts.getLineno());

            Object cur_case = null;  // to kill warning
            Object case_statements;

            mustMatchToken(Token.LP, "msg.no.paren.switch");
            decompiler.addToken(Token.LP);
            nf.addChildToBack(pn, expr(false));
            mustMatchToken(Token.RP, "msg.no.paren.after.switch");
            decompiler.addToken(Token.RP);
            mustMatchToken(Token.LC, "msg.no.brace.switch");
            decompiler.addEOL(Token.LC);

            while ((tt = ts.getToken()) != Token.RC && tt != Token.EOF) {
                switch(tt) {
                case Token.CASE:
                    decompiler.addToken(Token.CASE);
                    cur_case = nf.createUnary(Token.CASE, expr(false));
                    decompiler.addEOL(Token.COLON);
                    break;

                case Token.DEFAULT:
                    cur_case = nf.createLeaf(Token.DEFAULT);
                    decompiler.addToken(Token.DEFAULT);
                    decompiler.addEOL(Token.COLON);
                    // XXX check that there isn't more than one default
                    break;

                default:
                    reportError("msg.bad.switch");
                    break;
                }
                mustMatchToken(Token.COLON, "msg.no.colon.case");

                case_statements = nf.createLeaf(Token.BLOCK);

                while ((tt = ts.peekToken()) != Token.RC && tt != Token.CASE &&
                        tt != Token.DEFAULT && tt != Token.EOF)
                {
                    nf.addChildToBack(case_statements, statement());
                }
                // assert cur_case
                nf.addChildToBack(cur_case, case_statements);

                nf.addChildToBack(pn, cur_case);
            }
            decompiler.addEOL(Token.RC);
            break;
        }

        case Token.WHILE: {
            skipsemi = true;

            decompiler.addToken(Token.WHILE);
            int lineno = ts.getLineno();
            Object cond = condition();
            decompiler.addEOL(Token.LC);
            Object body = statement();
            decompiler.addEOL(Token.RC);

            pn = nf.createWhile(cond, body, lineno);
            break;

        }

        case Token.DO: {
            decompiler.addToken(Token.DO);
            decompiler.addEOL(Token.LC);

            int lineno = ts.getLineno();

            Object body = statement();

            decompiler.addToken(Token.RC);
            mustMatchToken(Token.WHILE, "msg.no.while.do");
            decompiler.addToken(Token.WHILE);
            Object cond = condition();

            pn = nf.createDoWhile(body, cond, lineno);
            break;
        }

        case Token.FOR: {
            skipsemi = true;

            decompiler.addToken(Token.FOR);
            int lineno = ts.getLineno();

            Object init;  // Node init is also foo in 'foo in Object'
            Object cond;  // Node cond is also object in 'foo in Object'
            Object incr = null; // to kill warning
            Object body;

            mustMatchToken(Token.LP, "msg.no.paren.for");
            decompiler.addToken(Token.LP);
            tt = ts.peekToken();
            if (tt == Token.SEMI) {
                init = nf.createLeaf(Token.EMPTY);
            } else {
                if (tt == Token.VAR) {
                    // set init to a var list or initial
                    ts.getToken();    // throw away the 'var' token
                    init = variables(true);
                }
                else {
                    init = expr(true);
                }
            }

            if (ts.matchToken(Token.IN)) {
                decompiler.addToken(Token.IN);
                // 'cond' is the object over which we're iterating
                cond = expr(false);
            } else {  // ordinary for loop
                mustMatchToken(Token.SEMI, "msg.no.semi.for");
                decompiler.addToken(Token.SEMI);
                if (ts.peekToken() == Token.SEMI) {
                    // no loop condition
                    cond = nf.createLeaf(Token.EMPTY);
                } else {
                    cond = expr(false);
                }

                mustMatchToken(Token.SEMI, "msg.no.semi.for.cond");
                decompiler.addToken(Token.SEMI);
                if (ts.peekToken() == Token.RP) {
                    incr = nf.createLeaf(Token.EMPTY);
                } else {
                    incr = expr(false);
                }
            }

            mustMatchToken(Token.RP, "msg.no.paren.for.ctrl");
            decompiler.addToken(Token.RP);
            decompiler.addEOL(Token.LC);
            body = statement();
            decompiler.addEOL(Token.RC);

            if (incr == null) {
                // cond could be null if 'in obj' got eaten by the init node.
                pn = nf.createForIn(init, cond, body, lineno);
            } else {
                pn = nf.createFor(init, cond, incr, body, lineno);
            }
            break;
        }

        case Token.TRY: {
            int lineno = ts.getLineno();

            Object tryblock;
            Object catchblocks = null;
            Object finallyblock = null;

            skipsemi = true;
            decompiler.addToken(Token.TRY);
            decompiler.addEOL(Token.LC);
            tryblock = statement();
            decompiler.addEOL(Token.RC);

            catchblocks = nf.createLeaf(Token.BLOCK);

            boolean sawDefaultCatch = false;
            int peek = ts.peekToken();
            if (peek == Token.CATCH) {
                while (ts.matchToken(Token.CATCH)) {
                    if (sawDefaultCatch) {
                        reportError("msg.catch.unreachable");
                    }
                    decompiler.addToken(Token.CATCH);
                    mustMatchToken(Token.LP, "msg.no.paren.catch");
                    decompiler.addToken(Token.LP);

                    mustMatchToken(Token.NAME, "msg.bad.catchcond");
                    String varName = ts.getString();
                    decompiler.addName(varName);

                    Object catchCond = null;
                    if (ts.matchToken(Token.IF)) {
                        decompiler.addToken(Token.IF);
                        catchCond = expr(false);
                    } else {
                        sawDefaultCatch = true;
                    }

                    mustMatchToken(Token.RP, "msg.bad.catchcond");
                    decompiler.addToken(Token.RP);
                    mustMatchToken(Token.LC, "msg.no.brace.catchblock");
                    decompiler.addEOL(Token.LC);

                    nf.addChildToBack(catchblocks,
                        nf.createCatch(varName, catchCond,
                                       statements(),
                                       ts.getLineno()));

                    mustMatchToken(Token.RC, "msg.no.brace.after.body");
                    decompiler.addEOL(Token.RC);
                }
            } else if (peek != Token.FINALLY) {
                mustMatchToken(Token.FINALLY, "msg.try.no.catchfinally");
            }

            if (ts.matchToken(Token.FINALLY)) {
                decompiler.addToken(Token.FINALLY);
                decompiler.addEOL(Token.LC);
                finallyblock = statement();
                decompiler.addEOL(Token.RC);
            }

            pn = nf.createTryCatchFinally(tryblock, catchblocks,
                                          finallyblock, lineno);

            break;
        }
        case Token.THROW: {
            int lineno = ts.getLineno();
            decompiler.addToken(Token.THROW);
            pn = nf.createThrow(expr(false), lineno);
            if (lineno == ts.getLineno())
                checkWellTerminated();
            break;
        }
        case Token.BREAK: {
            int lineno = ts.getLineno();

            decompiler.addToken(Token.BREAK);

            // matchLabel only matches if there is one
            String label = matchLabel();
            if (label != null) {
                decompiler.addName(label);
            }
            pn = nf.createBreak(label, lineno);
            break;
        }
        case Token.CONTINUE: {
            int lineno = ts.getLineno();

            decompiler.addToken(Token.CONTINUE);

            // matchLabel only matches if there is one
            String label = matchLabel();
            if (label != null) {
                decompiler.addName(label);
            }
            pn = nf.createContinue(label, lineno);
            break;
        }
        case Token.WITH: {
            skipsemi = true;

            decompiler.addToken(Token.WITH);
            int lineno = ts.getLineno();
            mustMatchToken(Token.LP, "msg.no.paren.with");
            decompiler.addToken(Token.LP);
            Object obj = expr(false);
            mustMatchToken(Token.RP, "msg.no.paren.after.with");
            decompiler.addToken(Token.RP);
            decompiler.addEOL(Token.LC);

            ++nestingOfWith;
            Object body;
            try {
                body = statement();
            } finally {
                --nestingOfWith;
            }

            decompiler.addEOL(Token.RC);

            pn = nf.createWith(obj, body, lineno);
            break;
        }
        case Token.VAR: {
            int lineno = ts.getLineno();
            pn = variables(false);
            if (ts.getLineno() == lineno)
                checkWellTerminated();
            break;
        }
        case Token.RETURN: {
            Object retExpr = null;

            decompiler.addToken(Token.RETURN);

            // bail if we're not in a (toplevel) function
            if ((ts.flags & ts.TSF_FUNCTION) == 0)
                reportError("msg.bad.return");

            /* This is ugly, but we don't want to require a semicolon. */
            ts.flags |= ts.TSF_REGEXP;
            tt = ts.peekTokenSameLine();
            ts.flags &= ~ts.TSF_REGEXP;

            int lineno = ts.getLineno();
            if (tt != Token.EOF && tt != Token.EOL && tt != Token.SEMI && tt != Token.RC) {
                retExpr = expr(false);
                if (ts.getLineno() == lineno)
                    checkWellTerminated();
                ts.flags |= ts.TSF_RETURN_EXPR;
            } else {
                ts.flags |= ts.TSF_RETURN_VOID;
            }

            // XXX ASSERT pn
            pn = nf.createReturn(retExpr, lineno);
            break;
        }
        case Token.LC:
            skipsemi = true;

            pn = statements();
            mustMatchToken(Token.RC, "msg.no.brace.block");
            break;

        case Token.ERROR:
            // Fall thru, to have a node for error recovery to work on
        case Token.EOL:
        case Token.SEMI:
            pn = nf.createLeaf(Token.EMPTY);
            skipsemi = true;
            break;

        case Token.FUNCTION: {
            pn = function(FunctionNode.FUNCTION_EXPRESSION_STATEMENT);
            break;
        }

        default: {
                int lastExprType = tt;
                int tokenno = ts.getTokenno();
                ts.ungetToken(tt);
                int lineno = ts.getLineno();

                pn = expr(false);

                if (ts.peekToken() == Token.COLON) {
                    /* check that the last thing the tokenizer returned was a
                     * NAME and that only one token was consumed.
                     */
                    if (lastExprType != Token.NAME || (ts.getTokenno() != tokenno))
                        reportError("msg.bad.label");

                    ts.getToken();  // eat the COLON

                    /* in the C source, the label is associated with the
                     * statement that follows:
                     *                nf.addChildToBack(pn, statement());
                     */
                    String name = ts.getString();
                    pn = nf.createLabel(name, lineno);

                    // depend on decompiling lookahead to guess that that
                    // last name was a label.
                    decompiler.addEOL(Token.COLON);
                    return pn;
                }

                pn = nf.createExprStatement(pn, lineno);

                if (ts.getLineno() == lineno) {
                    checkWellTerminated();
                }
                break;
            }
        }
        ts.matchToken(Token.SEMI);
        if (!skipsemi) {
            decompiler.addEOL(Token.SEMI);
        }

        return pn;
    }

    private Object variables(boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = nf.createVariables(ts.getLineno());
        boolean first = true;

        decompiler.addToken(Token.VAR);

        for (;;) {
            Object name;
            Object init;
            mustMatchToken(Token.NAME, "msg.bad.var");
            String s = ts.getString();

            if (!first)
                decompiler.addToken(Token.COMMA);
            first = false;

            decompiler.addName(s);
            currentScriptOrFn.addVar(s);
            name = nf.createName(s);

            // omitted check for argument hiding

            if (ts.matchToken(Token.ASSIGN)) {
                decompiler.addToken(Token.ASSIGN);

                init = assignExpr(inForInit);
                nf.addChildToBack(name, init);
            }
            nf.addChildToBack(pn, name);
            if (!ts.matchToken(Token.COMMA))
                break;
        }
        return pn;
    }

    private Object expr(boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = assignExpr(inForInit);
        while (ts.matchToken(Token.COMMA)) {
            decompiler.addToken(Token.COMMA);
            pn = nf.createBinary(Token.COMMA, pn, assignExpr(inForInit));
        }
        return pn;
    }

    private Object assignExpr(boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = condExpr(inForInit);

        int tt = ts.peekToken();
        // omitted: "invalid assignment left-hand side" check.
        if (tt == Token.ASSIGN) {
            ts.getToken();
            decompiler.addToken(Token.ASSIGN);
            pn = nf.createAssignment(pn, assignExpr(inForInit));
        } else if (tt == Token.ASSIGNOP) {
            ts.getToken();
            int op = ts.getOp();
            decompiler.addAssignOp(op);
            pn = nf.createAssignmentOp(op, pn, assignExpr(inForInit));
        }

        return pn;
    }

    private Object condExpr(boolean inForInit)
        throws IOException, ParserException
    {
        Object ifTrue;
        Object ifFalse;

        Object pn = orExpr(inForInit);

        if (ts.matchToken(Token.HOOK)) {
            decompiler.addToken(Token.HOOK);
            ifTrue = assignExpr(false);
            mustMatchToken(Token.COLON, "msg.no.colon.cond");
            decompiler.addToken(Token.COLON);
            ifFalse = assignExpr(inForInit);
            return nf.createCondExpr(pn, ifTrue, ifFalse);
        }

        return pn;
    }

    private Object orExpr(boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = andExpr(inForInit);
        if (ts.matchToken(Token.OR)) {
            decompiler.addToken(Token.OR);
            pn = nf.createBinary(Token.OR, pn, orExpr(inForInit));
        }

        return pn;
    }

    private Object andExpr(boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = bitOrExpr(inForInit);
        if (ts.matchToken(Token.AND)) {
            decompiler.addToken(Token.AND);
            pn = nf.createBinary(Token.AND, pn, andExpr(inForInit));
        }

        return pn;
    }

    private Object bitOrExpr(boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = bitXorExpr(inForInit);
        while (ts.matchToken(Token.BITOR)) {
            decompiler.addToken(Token.BITOR);
            pn = nf.createBinary(Token.BITOR, pn, bitXorExpr(inForInit));
        }
        return pn;
    }

    private Object bitXorExpr(boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = bitAndExpr(inForInit);
        while (ts.matchToken(Token.BITXOR)) {
            decompiler.addToken(Token.BITXOR);
            pn = nf.createBinary(Token.BITXOR, pn, bitAndExpr(inForInit));
        }
        return pn;
    }

    private Object bitAndExpr(boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = eqExpr(inForInit);
        while (ts.matchToken(Token.BITAND)) {
            decompiler.addToken(Token.BITAND);
            pn = nf.createBinary(Token.BITAND, pn, eqExpr(inForInit));
        }
        return pn;
    }

    private Object eqExpr(boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = relExpr(inForInit);
        for (;;) {
            int tt = ts.peekToken();
            switch (tt) {
              case Token.EQ:
              case Token.NE:
              case Token.SHEQ:
              case Token.SHNE:
                ts.getToken();
                int decompilerToken = tt;
                int parseToken = tt;
                if (compilerEnv.languageVersion == Context.VERSION_1_2) {
                    // JavaScript 1.2 uses shallow equality for == and != .
                    // In addition, convert === and !== for decompiler into
                    // == and != since the decompiler is supposed to show
                    // canonical source and in 1.2 ===, !== are allowed
                    // only as an alias to ==, !=.
                    switch (tt) {
                      case Token.EQ:
                        parseToken = Token.SHEQ;
                        break;
                      case Token.NE:
                        parseToken = Token.SHNE;
                        break;
                      case Token.SHEQ:
                        decompilerToken = Token.EQ;
                        break;
                      case Token.SHNE:
                        decompilerToken = Token.NE;
                        break;
                    }
                }
                decompiler.addToken(decompilerToken);
                pn = nf.createBinary(parseToken, pn, relExpr(inForInit));
                continue;
            }
            break;
        }
        return pn;
    }

    private Object relExpr(boolean inForInit)
        throws IOException, ParserException
    {
        Object pn = shiftExpr();
        for (;;) {
            int tt = ts.peekToken();
            switch (tt) {
              case Token.IN:
                if (inForInit)
                    break;
                // fall through
              case Token.INSTANCEOF:
              case Token.LE:
              case Token.LT:
              case Token.GE:
              case Token.GT:
                ts.getToken();
                decompiler.addToken(tt);
                pn = nf.createBinary(tt, pn, shiftExpr());
                continue;
            }
            break;
        }
        return pn;
    }

    private Object shiftExpr()
        throws IOException, ParserException
    {
        Object pn = addExpr();
        for (;;) {
            int tt = ts.peekToken();
            switch (tt) {
              case Token.LSH:
              case Token.URSH:
              case Token.RSH:
                ts.getToken();
                decompiler.addToken(tt);
                pn = nf.createBinary(tt, pn, addExpr());
                continue;
            }
            break;
        }
        return pn;
    }

    private Object addExpr()
        throws IOException, ParserException
    {
        Object pn = mulExpr();
        for (;;) {
            int tt = ts.peekToken();
            if (tt == Token.ADD || tt == Token.SUB) {
                ts.getToken();
                decompiler.addToken(tt);
                // flushNewLines
                pn = nf.createBinary(tt, pn, mulExpr());
                continue;
            }
            break;
        }

        return pn;
    }

    private Object mulExpr()
        throws IOException, ParserException
    {
        Object pn = unaryExpr();
        for (;;) {
            int tt = ts.peekToken();
            switch (tt) {
              case Token.MUL:
              case Token.DIV:
              case Token.MOD:
                ts.getToken();
                decompiler.addToken(tt);
                pn = nf.createBinary(tt, pn, unaryExpr());
                continue;
            }
            break;
        }

        return pn;
    }

    private Object unaryExpr()
        throws IOException, ParserException
    {
        int tt;

        ts.flags |= ts.TSF_REGEXP;
        tt = ts.getToken();
        ts.flags &= ~ts.TSF_REGEXP;

        switch(tt) {
        case Token.VOID:
        case Token.NOT:
        case Token.BITNOT:
        case Token.TYPEOF:
            decompiler.addToken(tt);
            return nf.createUnary(tt, unaryExpr());

        case Token.ADD:
            // Convert to special POS token in decompiler and parse tree
            decompiler.addToken(Token.POS);
            return nf.createUnary(Token.POS, unaryExpr());

        case Token.SUB:
            // Convert to special NEG token in decompiler and parse tree
            decompiler.addToken(Token.NEG);
            return nf.createUnary(Token.NEG, unaryExpr());

        case Token.INC:
        case Token.DEC:
            decompiler.addToken(tt);
            return nf.createIncDec(tt, false, memberExpr(true));

        case Token.DELPROP:
            decompiler.addToken(Token.DELPROP);
            return nf.createUnary(Token.DELPROP, unaryExpr());

        case Token.ERROR:
            break;

        default:
            ts.ungetToken(tt);

            int lineno = ts.getLineno();

            Object pn = memberExpr(true);

            /* don't look across a newline boundary for a postfix incop.

             * the rhino scanner seems to work differently than the js
             * scanner here; in js, it works to have the line number check
             * precede the peekToken calls.  It'd be better if they had
             * similar behavior...
             */
            int peeked;
            if (((peeked = ts.peekToken()) == Token.INC ||
                 peeked == Token.DEC) &&
                ts.getLineno() == lineno)
            {
                int pf = ts.getToken();
                decompiler.addToken(pf);
                return nf.createIncDec(pf, true, pn);
            }
            return pn;
        }
        return nf.createName("err"); // Only reached on error.  Try to continue.

    }

    private Object argumentList(Object listNode)
        throws IOException, ParserException
    {
        boolean matched;
        ts.flags |= ts.TSF_REGEXP;
        matched = ts.matchToken(Token.RP);
        ts.flags &= ~ts.TSF_REGEXP;
        if (!matched) {
            boolean first = true;
            do {
                if (!first)
                    decompiler.addToken(Token.COMMA);
                first = false;
                nf.addChildToBack(listNode, assignExpr(false));
            } while (ts.matchToken(Token.COMMA));

            mustMatchToken(Token.RP, "msg.no.paren.arg");
        }
        decompiler.addToken(Token.RP);
        return listNode;
    }

    private Object memberExpr(boolean allowCallSyntax)
        throws IOException, ParserException
    {
        int tt;

        Object pn;

        /* Check for new expressions. */
        ts.flags |= ts.TSF_REGEXP;
        tt = ts.peekToken();
        ts.flags &= ~ts.TSF_REGEXP;
        if (tt == Token.NEW) {
            /* Eat the NEW token. */
            ts.getToken();
            decompiler.addToken(Token.NEW);

            /* Make a NEW node to append to. */
            pn = nf.createLeaf(Token.NEW);
            nf.addChildToBack(pn, memberExpr(false));

            if (ts.matchToken(Token.LP)) {
                decompiler.addToken(Token.LP);
                /* Add the arguments to pn, if any are supplied. */
                pn = argumentList(pn);
            }

            /* XXX there's a check in the C source against
             * "too many constructor arguments" - how many
             * do we claim to support?
             */

            /* Experimental syntax:  allow an object literal to follow a new expression,
             * which will mean a kind of anonymous class built with the JavaAdapter.
             * the object literal will be passed as an additional argument to the constructor.
             */
            tt = ts.peekToken();
            if (tt == Token.LC) {
                nf.addChildToBack(pn, primaryExpr());
            }
        } else {
            pn = primaryExpr();
        }

        return memberExprTail(allowCallSyntax, pn);
    }

    private Object memberExprTail(boolean allowCallSyntax, Object pn)
        throws IOException, ParserException
    {
        int tt;
        while ((tt = ts.getToken()) > Token.EOF) {
            if (tt == Token.DOT) {
                decompiler.addToken(Token.DOT);
                mustMatchToken(Token.NAME, "msg.no.name.after.dot");
                String s = ts.getString();
                decompiler.addName(s);
                pn = nf.createBinary(Token.DOT, pn,
                                     nf.createName(ts.getString()));
                /* pn = nf.createBinary(Token.DOT, pn, memberExpr())
                 * is the version in Brendan's IR C version.  Not in ECMA...
                 * does it reflect the 'new' operator syntax he mentioned?
                 */
            } else if (tt == Token.LB) {
                decompiler.addToken(Token.LB);
                pn = nf.createBinary(Token.LB, pn, expr(false));

                mustMatchToken(Token.RB, "msg.no.bracket.index");
                decompiler.addToken(Token.RB);
            } else if (allowCallSyntax && tt == Token.LP) {
                /* make a call node */

                pn = nf.createUnary(Token.CALL, pn);
                decompiler.addToken(Token.LP);

                /* Add the arguments to pn, if any are supplied. */
                pn = argumentList(pn);
            } else {
                ts.ungetToken(tt);

                break;
            }
        }
        return pn;
    }

    private Object primaryExpr()
        throws IOException, ParserException
    {
        int tt;

        Object pn;

        ts.flags |= ts.TSF_REGEXP;
        tt = ts.getToken();
        ts.flags &= ~ts.TSF_REGEXP;

        switch(tt) {

        case Token.FUNCTION:
            return function(FunctionNode.FUNCTION_EXPRESSION);

        case Token.LB:
            {
                decompiler.addToken(Token.LB);
                pn = nf.createLeaf(Token.ARRAYLIT);

                ts.flags |= ts.TSF_REGEXP;
                boolean matched = ts.matchToken(Token.RB);
                ts.flags &= ~ts.TSF_REGEXP;

                if (!matched) {
                    boolean first = true;
                    do {
                        ts.flags |= ts.TSF_REGEXP;
                        tt = ts.peekToken();
                        ts.flags &= ~ts.TSF_REGEXP;

                        if (!first)
                            decompiler.addToken(Token.COMMA);
                        else
                            first = false;

                        if (tt == Token.RB) {  // to fix [,,,].length behavior...
                            break;
                        }

                        if (tt == Token.COMMA) {
                            nf.addChildToBack(pn,
                                nf.createLeaf(Token.UNDEFINED));
                        } else {
                            nf.addChildToBack(pn, assignExpr(false));
                        }

                    } while (ts.matchToken(Token.COMMA));
                    mustMatchToken(Token.RB, "msg.no.bracket.arg");
                }
                decompiler.addToken(Token.RB);
                return nf.createArrayLiteral(pn);
            }

        case Token.LC: {
            pn = nf.createLeaf(Token.OBJLIT);

            decompiler.addToken(Token.LC);
            if (!ts.matchToken(Token.RC)) {

                boolean first = true;
            commaloop:
                do {
                    Object property;

                    if (!first)
                        decompiler.addToken(Token.COMMA);
                    else
                        first = false;

                    tt = ts.getToken();
                    switch(tt) {
                        // map NAMEs to STRINGs in object literal context.
                    case Token.NAME:
                    case Token.STRING:
                        String s = ts.getString();
                        decompiler.addName(s);
                        property = nf.createString(ts.getString());
                        break;
                    case Token.NUMBER:
                        double n = ts.getNumber();
                        decompiler.addNumber(n);
                        property = nf.createNumber(n);
                        break;
                    case Token.RC:
                        // trailing comma is OK.
                        ts.ungetToken(tt);
                        break commaloop;
                    default:
                        reportError("msg.bad.prop");
                        break commaloop;
                    }
                    mustMatchToken(Token.COLON, "msg.no.colon.prop");

                    // OBJLIT is used as ':' in object literal for
                    // decompilation to solve spacing ambiguity.
                    decompiler.addToken(Token.OBJLIT);
                    nf.addChildToBack(pn, property);
                    nf.addChildToBack(pn, assignExpr(false));

                } while (ts.matchToken(Token.COMMA));

                mustMatchToken(Token.RC, "msg.no.brace.prop");
            }
            decompiler.addToken(Token.RC);
            return nf.createObjectLiteral(pn);
        }

        case Token.LP:

            /* Brendan's IR-jsparse.c makes a new node tagged with
             * TOK_LP here... I'm not sure I understand why.  Isn't
             * the grouping already implicit in the structure of the
             * parse tree?  also TOK_LP is already overloaded (I
             * think) in the C IR as 'function call.'  */
            decompiler.addToken(Token.LP);
            pn = expr(false);
            decompiler.addToken(Token.RP);
            mustMatchToken(Token.RP, "msg.no.paren");
            return pn;

        case Token.NAME:
            String name = ts.getString();
            decompiler.addName(name);
            return nf.createName(name);

        case Token.NUMBER:
            double n = ts.getNumber();
            decompiler.addNumber(n);
            return nf.createNumber(n);

        case Token.STRING:
            String s = ts.getString();
            decompiler.addString(s);
            return nf.createString(s);

        case Token.REGEXP:
        {
            String flags = ts.regExpFlags;
            ts.regExpFlags = null;
            String re = ts.getString();
            decompiler.addRegexp(re, flags);
            int index = currentScriptOrFn.addRegexp(re, flags);
            return nf.createRegExp(index);
        }

        case Token.NULL:
        case Token.THIS:
        case Token.FALSE:
        case Token.TRUE:
            decompiler.addToken(tt);
            return nf.createLeaf(tt);

        case Token.RESERVED:
            reportError("msg.reserved.id");
            break;

        case Token.ERROR:
            /* the scanner or one of its subroutines reported the error. */
            break;

        default:
            reportError("msg.syntax");
            break;

        }
        return null;    // should never reach here
    }

    CompilerEnvirons compilerEnv;
    private TokenStream ts;

    private IRFactory nf;

    private boolean ok; // Did the parse encounter an error?

    private ScriptOrFnNode currentScriptOrFn;

    private int nestingOfWith;

    private Decompiler decompiler;
    private String encodedSource;

}

// Exception to unwind
class ParserException extends RuntimeException { }
