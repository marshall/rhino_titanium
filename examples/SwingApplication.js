/*
 * SwingApplication.js - a translation into JavaScript of
 * SwingApplication.java, a java.sun.com Swing example.
 * 
 * @author Roger E Critchlow, Jr.
 */

importPackage(Packages.javax.swing);
importPackage(Packages.java.awt);
importPackage(Packages.java.awt.event);

function createComponents() {
    var labelPrefix = "Number of button clicks: ";
    var numClicks = 0;
    var label = new JLabel(labelPrefix + numClicks);
    var button = new JButton("I'm a Swing button!");
    button.setMnemonic(KeyEvent.VK_I);
    button.addActionListener(new ActionListener({
	actionPerformed : function() {
	    numClicks += 1;
	    label.setText(labelPrefix + numClicks);
	}
    }));
    label.setLabelFor(button);

    /*
     * An easy way to put space between a top-level container
     * and its contents is to put the contents in a JPanel
     * that has an "empty" border.
     */
    var pane = new JPanel();
    pane.setBorder(BorderFactory.createEmptyBorder(
                                                   30, //top
                                                   30, //left
                                                   10, //bottom
                                                   30) //right
		   );
    pane.setLayout(new GridLayout(0, 1));
    pane.add(button);
    pane.add(label);

    return pane;
}

try {
    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
} catch (e) { }

//Create the top-level container and add contents to it.
var frame = new JFrame("SwingApplication");
frame.getContentPane().add(createComponents(), BorderLayout.CENTER);

//Finish setting up the frame, and show it.
frame.addWindowListener(new WindowAdapter({
    windowClosing : function() {
	java.lang.System.exit(0);
    }
}) );
frame.pack();
frame.setVisible(true);



