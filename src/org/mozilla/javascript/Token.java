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
 * Roger Lawrence
 * Mike McCabe
 * Igor Bukanov
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

import java.io.*;

/**
 * This class implements the JavaScript scanner.
 *
 * It is based on the C source files jsscan.c and jsscan.h
 * in the jsref package.
 *
 * @see org.mozilla.javascript.Parser
 *
 * @author Mike McCabe
 * @author Brendan Eich
 */

public class Token
{

    // debug flags
    public static final boolean printTrees = false;
    static final boolean printICode = false;
    static final boolean printNames = printTrees || printICode;


    /**
     * Token types.  These values correspond to JSTokenType values in
     * jsscan.c.
     */

    public final static int
    // start enum
        ERROR       = -1, // well-known as the only code < EOF
        EOF         = 0,  // end of file token - (not EOF_CHAR)
        EOL         = 1,  // end of line

        // Interpreter reuses the following as bytecodes
        FIRST_BYTECODE_TOKEN = 2,

        POPV        = 2,
        ENTERWITH   = 3,
        LEAVEWITH   = 4,
        RETURN      = 5,
        GOTO        = 6,
        IFEQ        = 7,
        IFNE        = 8,
        SETNAME     = 9,
        BITOR       = 10,
        BITXOR      = 11,
        BITAND      = 12,
        EQ          = 13,
        NE          = 14,
        LT          = 15,
        LE          = 16,
        GT          = 17,
        GE          = 18,
        LSH         = 19,
        RSH         = 20,
        URSH        = 21,
        ADD         = 22,
        SUB         = 23,
        MUL         = 24,
        DIV         = 25,
        MOD         = 26,
        NOT         = 27,
        BITNOT      = 28,
        POS         = 29,
        NEG         = 30,
        NEW         = 31,
        DELPROP     = 32,
        TYPEOF      = 33,
        GETPROP     = 34,
        SETPROP     = 35,
        GETELEM     = 36,
        SETELEM     = 37,
        CALL        = 38,
        NAME        = 39,
        NUMBER      = 40,
        STRING      = 41,
        ZERO        = 42,
        ONE         = 43,
        NULL        = 44,
        THIS        = 45,
        FALSE       = 46,
        TRUE        = 47,
        SHEQ        = 48,   // shallow equality (===)
        SHNE        = 49,   // shallow inequality (!==)
        REGEXP      = 50,
        POP         = 51,
        BINDNAME    = 52,
        THROW       = 53,
        IN          = 54,
        INSTANCEOF  = 55,
        LOCAL_SAVE  = 56,
        LOCAL_LOAD  = 57,
        GETBASE     = 58,
        GETVAR      = 59,
        SETVAR      = 60,
        UNDEFINED   = 61,
        CATCH_SCOPE = 62,
        ENUM_INIT   = 63,
        ENUM_NEXT   = 64,
        ENUM_ID     = 65,
        THISFN      = 66,
        RETURN_POPV = 67, // to return result stored as popv in functions

        LAST_BYTECODE_TOKEN = 67,
        // End of interpreter bytecodes

        GETTHIS     = 68,
        TRY         = 69,
        SEMI        = 70,  // semicolon
        LB          = 71,  // left and right brackets
        RB          = 72,
        LC          = 73,  // left and right curlies (braces)
        RC          = 74,
        LP          = 75,  // left and right parentheses
        RP          = 76,
        COMMA       = 77,  // comma operator
        ASSIGN      = 78, // simple assignment  (=)
        ASSIGNOP    = 79, // assignment with operation (+= -= etc.)
        HOOK        = 80, // conditional (?:)
        COLON       = 81,
        OR          = 82, // logical or (||)
        AND         = 83, // logical and (&&)
        INC         = 84, // increment/decrement (++ --)
        DEC         = 85,
        DOT         = 86, // member operator (.)
        FUNCTION    = 87, // function keyword
        EXPORT      = 88, // export keyword
        IMPORT      = 89, // import keyword
        IF          = 90, // if keyword
        ELSE        = 91, // else keyword
        SWITCH      = 92, // switch keyword
        CASE        = 93, // case keyword
        DEFAULT     = 94, // default keyword
        WHILE       = 95, // while keyword
        DO          = 96, // do keyword
        FOR         = 97, // for keyword
        BREAK       = 98, // break keyword
        CONTINUE    = 99, // continue keyword
        VAR         = 100, // var keyword
        WITH        = 101, // with keyword
        CATCH       = 102, // catch keyword
        FINALLY     = 103, // finally keyword
        VOID        = 104, // void keyword
        RESERVED    = 105, // reserved keywords

        EMPTY       = 106,

        /* types used for the parse tree - these never get returned
         * by the scanner.
         */

        BLOCK       = 107, // statement block
        ARRAYLIT    = 108, // array literal
        OBJLIT      = 109, // object literal
        LABEL       = 110, // label
        TARGET      = 111,
        LOOP        = 112,
        EXPRSTMT    = 113,
        PARENT      = 114,
        JSR         = 115,
        NEWTEMP     = 116,
        USETEMP     = 117,
        SCRIPT      = 118,   // top-level node for entire script
        TYPEOFNAME  = 119,  // for typeof(simple-name)
        USE_STACK   = 120,
        SETPROP_OP  = 121, // x.y op= something
        SETELEM_OP  = 122, // x[y] op= something
        INIT_LIST   = 123,
        LOCAL_BLOCK = 124,

        LAST_TOKEN  = 124;

    public static String name(int token)
    {
        if (printNames) {
            switch (token) {
                case ERROR:           return "ERROR";
                case EOF:             return "EOF";
                case EOL:             return "EOL";
                case POPV:            return "POPV";
                case ENTERWITH:       return "ENTERWITH";
                case LEAVEWITH:       return "LEAVEWITH";
                case RETURN:          return "RETURN";
                case GOTO:            return "GOTO";
                case IFEQ:            return "IFEQ";
                case IFNE:            return "IFNE";
                case SETNAME:         return "SETNAME";
                case BITOR:           return "BITOR";
                case BITXOR:          return "BITXOR";
                case BITAND:          return "BITAND";
                case EQ:              return "EQ";
                case NE:              return "NE";
                case LT:              return "LT";
                case LE:              return "LE";
                case GT:              return "GT";
                case GE:              return "GE";
                case LSH:             return "LSH";
                case RSH:             return "RSH";
                case URSH:            return "URSH";
                case ADD:             return "ADD";
                case SUB:             return "SUB";
                case MUL:             return "MUL";
                case DIV:             return "DIV";
                case MOD:             return "MOD";
                case NOT:             return "NOT";
                case BITNOT:          return "BITNOT";
                case POS:             return "POS";
                case NEG:             return "NEG";
                case NEW:             return "NEW";
                case DELPROP:         return "DELPROP";
                case TYPEOF:          return "TYPEOF";
                case GETPROP:         return "GETPROP";
                case SETPROP:         return "SETPROP";
                case GETELEM:         return "GETELEM";
                case SETELEM:         return "SETELEM";
                case CALL:            return "CALL";
                case NAME:            return "NAME";
                case NUMBER:          return "NUMBER";
                case STRING:          return "STRING";
                case ZERO:            return "ZERO";
                case ONE:             return "ONE";
                case NULL:            return "NULL";
                case THIS:            return "THIS";
                case FALSE:           return "FALSE";
                case TRUE:            return "TRUE";
                case SHEQ:            return "SHEQ";
                case SHNE:            return "SHNE";
                case REGEXP:          return "OBJECT";
                case POP:             return "POP";
                case BINDNAME:        return "BINDNAME";
                case THROW:           return "THROW";
                case IN:              return "IN";
                case INSTANCEOF:      return "INSTANCEOF";
                case LOCAL_SAVE:      return "LOCAL_SAVE";
                case LOCAL_LOAD:      return "LOCAL_LOAD";
                case GETBASE:         return "GETBASE";
                case GETVAR:          return "GETVAR";
                case SETVAR:          return "SETVAR";
                case UNDEFINED:       return "UNDEFINED";
                case GETTHIS:         return "GETTHIS";
                case TRY:             return "TRY";
                case CATCH_SCOPE:     return "CATCH_SCOPE";
                case ENUM_INIT:       return "ENUM_INIT";
                case ENUM_NEXT:       return "ENUM_NEXT";
                case ENUM_ID:         return "ENUM_ID";
                case THISFN:          return "THISFN";
                case RETURN_POPV:     return "RETURN_POPV";
                case SEMI:            return "SEMI";
                case LB:              return "LB";
                case RB:              return "RB";
                case LC:              return "LC";
                case RC:              return "RC";
                case LP:              return "LP";
                case RP:              return "RP";
                case COMMA:           return "COMMA";
                case ASSIGN:          return "ASSIGN";
                case ASSIGNOP:        return "ASSIGNOP";
                case HOOK:            return "HOOK";
                case COLON:           return "COLON";
                case OR:              return "OR";
                case AND:             return "AND";
                case INC:             return "INC";
                case DEC:             return "DEC";
                case DOT:             return "DOT";
                case FUNCTION:        return "FUNCTION";
                case EXPORT:          return "EXPORT";
                case IMPORT:          return "IMPORT";
                case IF:              return "IF";
                case ELSE:            return "ELSE";
                case SWITCH:          return "SWITCH";
                case CASE:            return "CASE";
                case DEFAULT:         return "DEFAULT";
                case WHILE:           return "WHILE";
                case DO:              return "DO";
                case FOR:             return "FOR";
                case BREAK:           return "BREAK";
                case CONTINUE:        return "CONTINUE";
                case VAR:             return "VAR";
                case WITH:            return "WITH";
                case CATCH:           return "CATCH";
                case FINALLY:         return "FINALLY";
                case RESERVED:        return "RESERVED";
                case EMPTY:           return "EMPTY";
                case BLOCK:           return "BLOCK";
                case ARRAYLIT:        return "ARRAYLIT";
                case OBJLIT:          return "OBJLIT";
                case LABEL:           return "LABEL";
                case TARGET:          return "TARGET";
                case LOOP:            return "LOOP";
                case EXPRSTMT:        return "EXPRSTMT";
                case PARENT:          return "PARENT";
                case JSR:             return "JSR";
                case NEWTEMP:         return "NEWTEMP";
                case USETEMP:         return "USETEMP";
                case SCRIPT:          return "SCRIPT";
                case TYPEOFNAME:      return "TYPEOFNAME";
                case USE_STACK:       return "USE_STACK";
                case SETPROP_OP:      return "SETPROP_OP";
                case SETELEM_OP:      return "SETELEM_OP";
                case INIT_LIST:       return "INIT_LIST";
                case LOCAL_BLOCK:     return "LOCAL_BLOCK";
            }
            return "<unknown="+token+">";
        }
        return null;
    }
}
