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
import org.mozilla.classfile.JavaVariable;

final class OptLocalVariable implements JavaVariable {

    public OptLocalVariable(String name, boolean isParameter) {
        itsName = name;
        itsIsParameter = isParameter;
        // If the variable is a parameter, it could have any type.
        // If it is from a "var" statement, its typeEvent will be set
        // when we see the setVar node.
        int typeEvent = isParameter ? TypeEvent.AnyType : TypeEvent.NoType;
        itsTypeUnion = new TypeEvent(typeEvent);
    }

    public String toString() {
        return "LocalVariable : '" + getName()
                    + "', index = " + getIndex()
                    + ", LiveAcrossCall = " + itsLiveAcrossCall
                    + ", isNumber = " + itsIsNumber
                    + ", isParameter = " + isParameter()
                    + ", JRegister = " + itsJRegister;
    }

    public String getName() {
        return itsName;
    }

    public String getTypeDescriptor() {
        return isNumber() ? "D" : "Ljava/lang/Object;";
    }

    public short getJRegister() {
        return itsJRegister;
    }

    void assignJRegister(short aJReg) {
        itsJRegister = aJReg;
    }

    /**
     * Get the offset into the bytecode where the variable becomes live.
     * Used for generating the local variable table.
     */
    public int getStartPC() {
        return initPC;
    }

    int getIndex() {
        return itsIndex;
    }

    void setIndex(int index) {
        itsIndex = index;
    }

    /**
     * Set the offset into the bytecode where the variable becomes live.
     * Used for generating the local variable table.
     */
    void setStartPC(int pc) {
        initPC = pc;
    }

    void setIsNumber()      { itsIsNumber = true; }
    boolean isNumber()      { return itsIsNumber; }

    boolean isParameter()   { return itsIsParameter; }

    void markLiveAcrossCall()     { itsLiveAcrossCall = true; }
    void clearLiveAcrossCall()    { itsLiveAcrossCall = false; }
    boolean isLiveAcrossCall()    { return itsLiveAcrossCall; }

    boolean assignType(int aType) {
        return itsTypeUnion.add(aType);
    }

    int getTypeUnion() {
        return itsTypeUnion.getEvent();
    }

    private String itsName;
    private boolean itsIsParameter;
    private int itsIndex = -1;

    private short itsJRegister = -1;   // unassigned

    private boolean itsLiveAcrossCall;
    private boolean itsIsNumber;

    private TypeEvent itsTypeUnion;        // the union of all assigned types

    private int initPC;
}
