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

public class FatBlock {

    public FatBlock(IRFactory irFactory, int startNodeIndex, int endNodeIndex,
                    Node[] statementNodes)
    {
        itsShadowOfFormerSelf = new Block(irFactory, startNodeIndex,
                                          endNodeIndex, statementNodes);
    }

    public Node getEndNode()
        { return itsShadowOfFormerSelf.getEndNode(); }

    public Block getSlimmerSelf()
        { return itsShadowOfFormerSelf; }

    private Block[] reduceToArray(ObjToIntMap map)
    {
        Block[] result = null;
        if (!map.isEmpty()) {
            result = new Block[map.size()];
            int i = 0;
            ObjToIntMap.Iterator iter = map.newIterator();
            for (iter.start(); !iter.done(); iter.next()) {
                FatBlock fb = (FatBlock)(iter.getKey());
                result[i++] = fb.itsShadowOfFormerSelf;
            }
        }
        return result;
    }

    Block diet()
    {
        itsShadowOfFormerSelf.setSuccessorList(reduceToArray(itsSuccessors));
        itsShadowOfFormerSelf.setPredecessorList(reduceToArray(itsPredecessors));
        return itsShadowOfFormerSelf;
    }

    public void addSuccessor(FatBlock b)  { itsSuccessors.put(b, 0); }
    public void addPredecessor(FatBlock b)  { itsPredecessors.put(b, 0); }

        // all the Blocks that come immediately after this
    private ObjToIntMap itsSuccessors = new ObjToIntMap();
        // all the Blocks that come immediately before this
    private ObjToIntMap itsPredecessors = new ObjToIntMap();

    private Block itsShadowOfFormerSelf;


}

