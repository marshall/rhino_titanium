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
 * Copyright (C) 1997-2000 Netscape Communications Corporation. All
 * Rights Reserved.
 *
 * Contributor(s):
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

package org.mozilla.javascript.xmlimpl;

import org.mozilla.javascript.*;
import org.mozilla.javascript.xml.*;

import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;

public final class XMLLibImpl extends XMLLib
{
    private Scriptable globalScope;

    XML xmlPrototype;
    XMLList xmlListPrototype;
    Namespace namespacePrototype;
    QName qnamePrototype;


    // Environment settings...
    boolean ignoreComments;
    boolean ignoreProcessingInstructions;
    boolean ignoreWhitespace;
    boolean prettyPrinting;
    int prettyIndent;

    Scriptable globalScope()
    {
        return globalScope;
    }

    private XMLLibImpl(Scriptable globalScope)
    {
        this.globalScope = globalScope;
        defaultSettings();
    }

    public static void init(Context cx, Scriptable scope, boolean sealed)
    {
        XMLLibImpl lib = new XMLLibImpl(scope);
        XMLLib bound = lib.bindToScope(scope);
        if (bound == lib) {
            lib.exportToScope(sealed);
        }
    }

    private void exportToScope(boolean sealed)
    {
        xmlPrototype = XML.createEmptyXML(this);
        xmlListPrototype = new XMLList(this);
        namespacePrototype = new Namespace(this, "", "");
        qnamePrototype = new QName(this, "", "", "");

        xmlPrototype.exportAsJSClass(sealed);
        xmlListPrototype.exportAsJSClass(sealed);
        namespacePrototype.exportAsJSClass(sealed);
        qnamePrototype.exportAsJSClass(sealed);
    }

    void defaultSettings()
    {
        ignoreComments = true;
        ignoreProcessingInstructions = true;
        ignoreWhitespace = true;
        prettyPrinting = true;
        prettyIndent = 2;
    }

    boolean ignoreComments()
    {
        return ignoreComments;
    }

    boolean ignoreProcessingInstructions()
    {
        return ignoreProcessingInstructions;
    }

    boolean ignoreWhitespace()
    {
        return ignoreWhitespace;
    }

    boolean prettyPrinting()
    {
        return prettyPrinting;
    }

    int prettyIndent()
    {
        return prettyIndent;
    }

    private XMLName resolveName(Context cx, Object id)
    {
        if(id instanceof XMLName) return (XMLName)id;
        if (id instanceof QName) {
            QName qname = (QName)id;
            return XMLName.formProperty(qname.uri(), qname.localName());
        }

        String name = ScriptRuntime.toString(id);
        boolean isAttributeName = false;
        if (name.length() != 0 && name.charAt(0) == '@') {
            name = name.substring(1);
            isAttributeName = true;
        }

        String uri = null;
        if(!name.equals("*")) {
            if (isAttributeName) {
                uri = "";
            } else {
                uri = "";
                if (cx == null) {
                    cx = Context.getCurrentContext();
                }
                if (cx != null) {
                    Object defaultNS = ScriptRuntime.searchDefaultNamespace(cx);
                    if (defaultNS != null) {
                        if (defaultNS instanceof Namespace) {
                            uri = ((Namespace)defaultNS).uri();
                        } else {
                            // Should not happen but for now it could
                            // due to bad searchDefaultNamespace implementation.
                        }
                    }
                }
            }
        }

        XMLName xmlName = XMLName.formProperty(uri, name);
        if (isAttributeName) {
            xmlName.setAttributeName();
        }
        return xmlName;
    }

    private Namespace resolveNamespace(String prefix, Scriptable scope)
    {
        Namespace ns = null;
        Scriptable nsScope = scope;

        while (nsScope != null) {
            Object obj = ScriptableObject.getProperty(nsScope, prefix);
            if (obj instanceof Namespace) {
                ns = (Namespace)obj;
                break;
            }
            nsScope = nsScope.getParentScope();
        }

        if (ns == null) {
            // Only namespace type allowed to left of :: operator.
            throw Context.reportRuntimeError(
                ScriptRuntime.getMessage1("msg.namespace.expected", prefix));
        }

        return ns;
    }

    XMLName toAttributeNameImpl(Context cx, Object nameValue)
    {
        String uri;
        String localName;

        if (nameValue instanceof String) {
            uri = "";
            localName = (String)nameValue;
        } else if (nameValue instanceof XMLName) {
            XMLName xmlName = (XMLName)nameValue;
            if (!xmlName.isAttributeName()) {
                xmlName.setAttributeName();
            }
            return xmlName;
        } else if (nameValue instanceof QName) {
            QName qname = (QName)nameValue;
            uri = qname.uri();
            localName = qname.localName();
        } else if (nameValue instanceof Scriptable) {
            uri = "";
            localName = ScriptRuntime.toString(nameValue);
        } else {
            throw ScriptRuntime.typeError("Bad attribute name: "+nameValue);
        }
        XMLName xmlName = XMLName.formProperty(uri, localName);
        xmlName.setAttributeName();
        return xmlName;
    }

    XMLName toXMLName(Context cx, Object nameValue)
    {
        XMLName result;

        if (nameValue instanceof XMLName) {
            result = (XMLName)nameValue;
        } else if (nameValue instanceof QName) {
            QName qname = (QName)nameValue;
            result = XMLName.formProperty(qname.uri(), qname.localName());
        } else {
            String name = ScriptRuntime.toString(nameValue);
            result = toXMLNameFromString(cx, name);
        }

        return result;
    }

    /**
     * If value represents Uint32 index, make it available through
     * ScriptRuntime.lastUint32Result(cx) and return null.
     * Otherwise return the same value as toXMLName(cx, value).
     */
    XMLName toXMLNameOrIndex(Context cx, Object value)
    {
        XMLName result;

        if (value instanceof XMLName) {
            result = (XMLName)value;
        } else if (value instanceof String) {
            String str = (String)value;
            long test = ScriptRuntime.testUint32String(str);
            if (test >= 0) {
                ScriptRuntime.storeUint32Result(cx, test);
                result = null;
            } else {
                result = toXMLNameFromString(cx, str);
            }
        } else if (value instanceof Number) {
            double d = ((Number)value).doubleValue();
            long l = (long)d;
            if (l == d && 0 <= l && l <= 0xFFFFFFFFL) {
                ScriptRuntime.storeUint32Result(cx, l);
                result = null;
            } else {
                String str = ScriptRuntime.toString(d);
                result = toXMLNameFromString(cx, str);
            }
        } else if (value instanceof QName) {
            QName qname = (QName)value;
            String uri = qname.uri();
            boolean number = false;
            result = null;
            if (uri != null && uri.length() == 0) {
                // Only in this case qname.toString() can resemble uint32
                long test = ScriptRuntime.testUint32String(uri);
                if (test >= 0) {
                    ScriptRuntime.storeUint32Result(cx, test);
                    number = true;
                }
            }
            if (!number) {
                result = XMLName.formProperty(uri, qname.localName());
            }
        } else {
            String str = ScriptRuntime.toString(value);
            long test = ScriptRuntime.testUint32String(str);
            if (test >= 0) {
                ScriptRuntime.storeUint32Result(cx, test);
                result = null;
            } else {
                result = toXMLNameFromString(cx, str);
            }
        }

        return result;
    }

    XMLName toXMLNameFromString(Context cx, String name)
    {
        if (name == null)
            throw new IllegalArgumentException();

        int l = name.length();
        if (l != 0) {
            char firstChar = name.charAt(0);
            if (firstChar == '*') {
                if (l == 1) {
                    return XMLName.formStar();
                }
            } else if (firstChar == '@') {
                XMLName xmlName = XMLName.formProperty("", name.substring(1));
                xmlName.setAttributeName();
                return xmlName;
            }
        }

        String uri = getDefaultNamespaceURI(cx);

        return XMLName.formProperty(uri, name);
    }

    Namespace constructNamespace(Context cx, Object uriValue)
    {
        String prefix;
        String uri;

        if (uriValue instanceof Namespace) {
            Namespace ns = (Namespace)uriValue;
            prefix = ns.prefix();
            uri = ns.uri();
        } else if (uriValue instanceof QName) {
            QName qname = (QName)uriValue;
            uri = qname.uri();
            if (uri != null) {
                prefix = qname.prefix();
            } else {
                uri = qname.toString();
                prefix = null;
            }
        } else {
            uri = ScriptRuntime.toString(uriValue);
            prefix = (uri.length() == 0) ? "" : null;
        }

        return new Namespace(this, prefix, uri);
    }

    Namespace castToNamespace(Context cx, Object namescapeObj)
    {
        if (namescapeObj instanceof Namespace) {
            return (Namespace)namescapeObj;
        }
        return constructNamespace(cx, namescapeObj);
    }

    Namespace constructNamespace(Context cx)
    {
        return new Namespace(this, "", "");
    }

    public Namespace constructNamespace(Context cx, Object prefixValue,
                                        Object uriValue)
    {
        String prefix;
        String uri;

        if (uriValue instanceof QName) {
            QName qname = (QName)uriValue;
            uri = qname.uri();
            if (uri == null) {
                uri = qname.toString();
            }
        } else {
            uri = ScriptRuntime.toString(uriValue);
        }

        if (uri.length() == 0) {
            if (prefixValue == Undefined.instance) {
                prefix = "";
            } else {
                prefix = ScriptRuntime.toString(prefixValue);
                if (prefix.length() != 0) {
                    throw ScriptRuntime.constructError("TypeError",
                        "Illegal prefix '"+prefix+"' for 'no namespace'.");
                }
            }
        } else if (prefixValue == Undefined.instance) {
            prefix = "";
        } else if (!isXMLName(cx, prefixValue)) {
            prefix = "";
        } else {
            prefix = ScriptRuntime.toString(prefixValue);
        }

        return new Namespace(this, prefix, uri);
    }

    String getDefaultNamespaceURI(Context cx)
    {
        String uri = "";
        if (cx == null) {
            cx = Context.getCurrentContext();
        }
        if (cx != null) {
            Object ns = ScriptRuntime.searchDefaultNamespace(cx);
            if (ns != null) {
                if (ns instanceof Namespace) {
                    uri = ((Namespace)ns).uri();
                } else {
                    // Should not happen but for now it could
                    // due to bad searchDefaultNamespace implementation.
                }
            }
        }
        return uri;
    }

    Namespace getDefaultNamespace(Context cx)
    {
        if (cx == null) {
            cx = Context.getCurrentContext();
            if (cx == null) {
                return namespacePrototype;
            }
        }

        Namespace result;
        Object ns = ScriptRuntime.searchDefaultNamespace(cx);
        if (ns == null) {
            result = namespacePrototype;
        } else {
            if (ns instanceof Namespace) {
                result = (Namespace)ns;
            } else {
                // Should not happen but for now it could
                // due to bad searchDefaultNamespace implementation.
                result = namespacePrototype;
            }
        }
        return result;
    }

    QName castToQName(Context cx, Object qnameValue)
    {
        if (qnameValue instanceof QName) {
            return (QName)qnameValue;
        }
        return constructQName(cx, qnameValue);
    }

    QName constructQName(Context cx, Object nameValue)
    {
        QName result;

        if (nameValue instanceof QName) {
            QName qname = (QName)nameValue;
            result = new QName(this, qname.uri(), qname.localName(),
                               qname.prefix());
        } else {
            String localName = ScriptRuntime.toString(nameValue);
            result = constructQNameFromString(cx, localName);
        }

        return result;
    }

    /**
     * Optimized version of constructQName for String type
     */
    QName constructQNameFromString(Context cx, String localName)
    {
        if (localName == null)
            throw new IllegalArgumentException();

        String uri;
        String prefix;

        if ("*".equals(localName)) {
            uri = null;
            prefix = null;
        } else {
            Namespace ns = getDefaultNamespace(cx);
            uri = ns.uri();
            prefix = ns.prefix();
        }

        return new QName(this, uri, localName, prefix);
    }

    QName constructQName(Context cx, Object namespaceValue, Object nameValue)
    {
        String uri;
        String localName;
        String prefix;

        if (nameValue instanceof QName) {
            QName qname = (QName)nameValue;
            localName = qname.localName();
        } else {
            localName = ScriptRuntime.toString(nameValue);
        }

        Namespace ns;
        if (namespaceValue == Undefined.instance) {
            if ("*".equals(localName)) {
                ns = null;
            } else {
                ns = getDefaultNamespace(cx);
            }
        } else if (namespaceValue == null) {
            ns = null;
        } else if (namespaceValue instanceof Namespace) {
            ns = (Namespace)namespaceValue;
        } else {
            ns = constructNamespace(cx, namespaceValue);
        }

        if (ns == null) {
            uri = null;
            prefix = null;
        } else {
            uri = ns.uri();
            prefix = ns.prefix();
        }

        return new QName(this, uri, localName, prefix);
    }

    Object addXMLObjects(Context cx, XMLObject obj1, XMLObject obj2)
    {
        XMLList listToAdd = new XMLList(this);

        if (obj1 instanceof XMLList) {
            XMLList list1 = (XMLList)obj1;
            if (list1.length() == 1) {
                listToAdd.addToList(list1.item(0));
            } else {
                // Might be xmlFragment + xmlFragment + xmlFragment + ...;
                // then the result will be an XMLList which we want to be an
                // rValue and allow it to be assigned to an lvalue.
                listToAdd = new XMLList(this, obj1);
            }
        } else {
            listToAdd.addToList(((XML)obj1));
        }

        if (obj2 instanceof XMLList) {
            XMLList list2 = (XMLList)obj2;
            for (int i = 0; i < list2.length(); i++) {
                listToAdd.addToList(list2.item(i));
            }
        } else if (obj2 instanceof XML) {
            listToAdd.addToList(((XML)obj2));
        }

        return listToAdd;
    }

    //
    //
    // Overriding XMLLib methods
    //
    //

    /**
     * TODO: Implement this method!
     */
    public boolean isXMLName(Context cx, Object name)
    {
        // TODO: Check if qname.localName() matches NCName

        return true;
    }

    public Object toQualifiedName(String namespace,
                                  Object nameValue,
                                  Scriptable scope)
    {
        String uri;
        String localName;

        if (nameValue instanceof QName) {
            QName qname = (QName)nameValue;
            localName = qname.localName();
        } else {
            localName = ScriptRuntime.toString(nameValue);
        }

        if ("*".equals(namespace)) {
            uri = null;
        } else {
            uri = resolveNamespace(namespace, scope).uri();
        }

        return XMLName.formProperty(uri, localName);
    }

    public Object toAttributeName(Context cx, Object nameValue)
    {
        return toAttributeNameImpl(cx, nameValue);
    }

    public Object toDescendantsName(Context cx, Object name)
    {
        XMLName xmlName = resolveName(cx, name);
        xmlName.setDescendants();
        return xmlName;
    }

    /**
     * See E4X 11.1 PrimaryExpression : PropertyIdentifier production
     */
    public Reference xmlPrimaryReference(Object nameObject, Scriptable scope)
    {
        if (!(nameObject instanceof XMLName))
            throw new IllegalArgumentException();

        XMLName xmlName = (XMLName)nameObject;
        XMLObjectImpl xmlObj;
        for (;;) {
            // XML object can only present on scope chain as a wrapper
            // of XMLWithScope
            if (scope instanceof XMLWithScope) {
                xmlObj = (XMLObjectImpl)scope.getPrototype();
                if (xmlObj.hasXMLProperty(xmlName)) {
                    break;
                }
            }
            scope = scope.getParentScope();
            if (scope == null) {
                xmlObj = null;
                break;
            }
        }

        // xmlName == null corresponds to undefined
        return new XMLReference(xmlObj, xmlName);
    }

    /**
     * Escapes the reserved characters in a value of an attribute
     *
     * @param value Unescaped text
     * @return The escaped text
     */
    public String escapeAttributeValue(Object value)
    {
        String text = ScriptRuntime.toString(value);

        if (text.length() == 0) return text;

        XmlObject xo = XmlObject.Factory.newInstance();

        XmlCursor cursor = xo.newCursor();
        cursor.toNextToken();
        cursor.beginElement("a");
        cursor.insertAttributeWithValue("a", text);
        cursor.dispose();

        String elementText = xo.toString();
        int begin = elementText.indexOf('"') + 1;
        int end = elementText.lastIndexOf('"');
        return (begin < end) ? elementText.substring(begin, end) : "";
    }

    /**
     * Escapes the reserved characters in a value of a text node
     *
     * @param value Unescaped text
     * @return The escaped text
     */
    public String escapeTextValue(Object value)
    {
        if (value instanceof XMLObjectImpl) {
            return ((XMLObjectImpl)value).toXMLString();
        }

        String text = ScriptRuntime.toString(value);

        if (text.length() == 0) return text;

        XmlObject xo = XmlObject.Factory.newInstance();

        XmlCursor cursor = xo.newCursor();
        cursor.toNextToken();
        cursor.beginElement("a");
        cursor.insertChars(text);
        cursor.dispose();

        String elementText = xo.toString();
        int begin = elementText.indexOf('>') + 1;
        int end = elementText.lastIndexOf('<');
        return (begin < end) ? elementText.substring(begin, end) : "";
    }

    public Object toDefaultXmlNamespace(Context cx, Object uriValue)
    {
        return constructNamespace(cx, uriValue);
    }

    /**
     * This method exists in order to handle the difference between a XML element property
     * and a method on an XML Object that might have the same name.
     *
     * @param obj
     * @param id
     * @param scope
     * @param thisObj
     * @return
     */
    static Object getXmlMethod(Object obj, String id, Scriptable scope,
                               Scriptable thisObj)
    {
        Scriptable start;

        if (obj instanceof Scriptable) {
            start = (Scriptable) obj;
        } else {
            start = ScriptRuntime.toObject(scope, obj);
        }

        Scriptable m = start;
        do {
            if (m instanceof XMLObjectImpl) {
                XMLObjectImpl xmlObject = (XMLObjectImpl) m;
                Object result = xmlObject.getMethod(id);
                if (result != Scriptable.NOT_FOUND) {
                    return result;
                }
            }
            m = m.getPrototype();
        } while (m != null);

        return Undefined.instance;
    }
}
