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
 * The Original Code is Rhino JavaScript Debugger code, released
 * November 21, 2000.
 *
 * The Initial Developer of the Original Code is SeeBeyond Corporation.

 * Portions created by SeeBeyond are
 * Copyright (C) 2000 SeeBeyond Technology Corporation. All
 * Rights Reserved.
 *
 * Contributor(s):
 * Igor Bukanov
 * Matt Gould
 * Christopher Oliver
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

package org.mozilla.javascript.tools.debugger;


import org.mozilla.javascript.*;
import org.mozilla.javascript.debug.*;
import java.io.*;

public class Main {


    Dim dim;
    DebugGui debugGui;

    //
    // public interface
    //

    public Main(String title)
    {
        dim = new Dim();
        debugGui = new DebugGui(dim, title);
        dim.callback = debugGui;
    }

    public void doBreak() {
        dim.breakFlag = true;
    }

   /**
    *  Toggle Break-on-Exception behavior
    */
    public void setBreakOnExceptions(boolean value) {
        dim.breakOnExceptions = value;
    }

   /**
    *  Toggle Break-on-Enter behavior
    */
    public void setBreakOnEnter(boolean value) {
        dim.breakOnEnter = value;
    }

   /**
    *  Toggle Break-on-Return behavior
    */
    public void setBreakOnReturn(boolean value) {
        dim.breakOnReturn = value;
    }

    /**
     *
     * Remove all breakpoints
     */
    public void clearAllBreakpoints()
    {
        dim.clearAllBreakpoints();
    }

   /**
    *  Resume Execution
    */
    public void go()
    {
        dim.go();
    }

    public void setScopeProvider(ScopeProvider p) {
        dim.scopeProvider = p;
    }

    /**
     * Assign a Runnable object that will be invoked when the user
     * selects "Exit..." or closes the Debugger main window
     */
    public void setExitAction(Runnable r) {
        debugGui.exitAction = r;
    }

    /**
     * Get an input stream to the Debugger's internal Console window
     */

    public InputStream getIn() {
        return debugGui.console.getIn();
    }

    /**
     * Get an output stream to the Debugger's internal Console window
     */

    public PrintStream getOut() {
        return debugGui.console.getOut();
    }

    /**
     * Get an error stream to the Debugger's internal Console window
     */

    public PrintStream getErr() {
        return debugGui.console.getErr();
    }

    public static void main(String[] args)
        throws Exception
    {
        Main main = new Main("Rhino JavaScript Debugger");
        main.dim.breakFlag = true;
        main.setExitAction(new Runnable() {
                public void run() {
                    System.exit(0);
                }
            });
        System.setIn(main.getIn());
        System.setOut(main.getOut());
        System.setErr(main.getErr());
        main.dim.enableForAllNewContexts();
        main.setScopeProvider(new ScopeProvider() {
                public Scriptable getScope() {
                    return org.mozilla.javascript.tools.shell.Main.getScope();
                }
            });

        main.debugGui.pack();
        main.debugGui.setSize(600, 460);
        main.debugGui.setVisible(true);

        org.mozilla.javascript.tools.shell.Main.exec(args);
    }

}

