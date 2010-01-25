/*
epub2pdf, version 0.2 - Copyright 2010 Brendan C. LeFebvre

This file is part of epub2pdf.

epub2pdf is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

epub2pdf is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with epub2pdf.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.amphisoft.epub2pdf.content;

import static com.amphisoft.util.StringManip.nothingButSpaces;
import static com.amphisoft.util.StringManip.removeLineBreaks;
import static com.amphisoft.util.StringManip.changeLineBreaksToSpaces;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import org.apache.xml.resolver.Catalog;
import org.apache.xml.resolver.CatalogManager;
import org.apache.xml.resolver.tools.ResolvingXMLReader;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.amphisoft.epub.Epub;
import com.amphisoft.epub.content.XhtmlTags;
import com.amphisoft.epub.metadata.Ncx.NavPoint;
import com.amphisoft.epub2pdf.style.CssParser;
import com.amphisoft.epub2pdf.style.CssStyleMap;
import com.amphisoft.epub2pdf.style.StyleSpecText;
import com.amphisoft.pdf.ITAlignment;
import com.amphisoft.pdf.ITPageSize;
import com.lowagie.text.Anchor;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.ElementTags;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.List;
import com.lowagie.text.ListItem;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.RectangleReadOnly;
import com.lowagie.text.TextElementArray;
import com.lowagie.text.html.SAXmyHtmlHandler;
import com.lowagie.text.pdf.PdfDestination;
import com.lowagie.text.pdf.PdfOutline;

import static com.amphisoft.util.Print.*;
/**
 *
 * @author brendanl
 *
 */
public class XhtmlHandler extends SAXmyHtmlHandler {

	protected Chunk currentChunk = null;
	protected Document document;

	protected Stack<Element> stack = new Stack<Element>();;
	protected Stack<SaxElement> saxElementStack = new Stack<SaxElement>();
	protected String currentTag = "";
	protected String previousTag = "";
	protected File xhtmlDir = null;
	protected String currentFile = "";

	protected static int[] FONTSIZES = { 24, 18, 12, 10, 8, 6 };
	protected static float[] FONTMULTIPLIERS = { 2.0f, 1.5f, 1.0f, 0.83f, 0.75f, 0.5f };
	protected static TextFactory textFactory = new TextFactory();
	protected static int currentITextStyle = 0;
	protected static int defaultAlignment = Paragraph.ALIGN_JUSTIFIED;
	protected static Paragraph specialParagraph = null;
	protected static String[] nonPrintingTags = {"title","style"};
	protected static Rectangle pageSize = ITPageSize.FOXIT_ESLICK;
	protected static float marginTopPt = 4.0f;
	protected static float marginRightPt = 8.0f;
	protected static float marginBottomPt = 4.0f;
	protected static float marginLeftPt = 4.0f;

	private static CssParser cssParser;
	private static Epub sourceEpub = null;
	private static PdfOutline bookmarkRoot = null;

	static {
		cssParser = new CssParser();
	}

	// currently-active style declarations

	// some object "current-style"

	private boolean freshParagraph = false;;

	private boolean inStyleTag = false;
	private StringBuilder styleTagContents = null;
	CssStyleMap styleMap;

	@Override
	public void startDocument() {
		styleMap = new CssStyleMap();
	}

	@Override
	public void characters(char[] ch, int start, int length) {

		String content = new String(ch, start, length);

		String abridgedContent;

		if (content.length() <= 20) {
			abridgedContent = content;
		} else {
			abridgedContent = content.substring(0, 10);
			abridgedContent += " ... ";
			int tailStartIndex = content.length() - 10;
			int tailEndIndex = content.length();
			abridgedContent += content.substring(tailStartIndex, tailEndIndex);
		}

		//printlnerr("chars:[" + abridgedContent + "]");

		boolean printThese = true;
		for (String tag : nonPrintingTags) {
			if (tag.equals(currentTag)) {
				printThese = false;
				break;
			}
		}

		if (inStyleTag) {
			if (styleTagContents == null) {
				styleTagContents = new StringBuilder();
			}
			styleTagContents.append(content);
		} else if (printThese) {
			Font font = null;
			if (XhtmlTags.PRE.equals(currentTag) || XhtmlTags.TT.equals(currentTag)) {
				font = textFactory.getDefaultFontMono();
			} else {
				font = textFactory.getCurrentFont();
			}
			if (currentITextStyle != font.getStyle()) {
				font.setStyle(currentITextStyle);
			}

			if (!(XhtmlTags.PRE.equals(currentTag) || XhtmlTags.TT.equals(currentTag))) {
				if (stack.size() > 0 && stack.peek() instanceof Paragraph) {
					content = changeLineBreaksToSpaces(content);
				} else {
					content = removeLineBreaks(content);
				}
				if (nothingButSpaces(content)) {
					SaxElement tagInProgress = saxElementStack.peek();
					if (!tagInProgress.qName.equals("p")) {
						content = "";
					}
				}
				boolean leadingSpace =
					content.length()>=1 &&
					content.startsWith(" ") &&
					!freshParagraph;
					//content.charAt(1) != ' ';
					boolean trailingSpace =
						content.length()>1 &&
						content.endsWith(" "); //&&
						//content.charAt(content.length()-2) != ' ';
						content = content.trim();

						if (leadingSpace) {
							content = " " + content;
						}
						if (trailingSpace)
							content = content + " ";
			}

			if (content.length() > 0) {
				if (currentChunk != null) {
					if (specialParagraph != null || currentChunk.getFont().compareTo(font) != 0) {
						stack.push(currentChunk);
						currentChunk = null;
					} else {
						currentChunk.append(content);
					}
				} else {
					if (specialParagraph != null) {
						specialParagraph.add(new Chunk(content));
					} else {
						currentChunk = new Chunk(content,font);
					}
				}
			}

			freshParagraph = false;
		}
	}

	String stackStatus() {

		if (stack.size() == 0) {
			return "(empty)";
		} else {
			StringBuilder sB = new StringBuilder();
			for (Element e : stack) {
				sB.append(e.getClass().getSimpleName() + " ");
			}
			return sB.toString();
		}
	}

	int saxElemIdCounter = 1;
	int currentSaxElemId;

	@Override
	public void startElement(String uri, String localName, String qName,
			Attributes attributes) {
		/*
		if("ol".equals(qName) || "ul".equals(qName) || "li".equals(qName)) {
			System.err.print(qName + " ");
		}
		*/
		currentSaxElemId = saxElemIdCounter;

		Map<String,String> attrMap = new HashMap<String,String>();
		// parse attributes
		for (int ai = 0; ai < attributes.getLength(); ai++) {
			attrMap.put(attributes.getQName(ai), attributes.getValue(ai));
		}
		String idAttr = attrMap.get("id");
		if(idAttr == null) {
			idAttr = "";
		}
		SaxElement sE = new SaxElement(qName, saxElemIdCounter++, idAttr);
		saxElementStack.push(sE);

		try {
			currentITextStyle = Font.NORMAL;

			if (attrMap.get("class") != null) {
				String[] elemClasses = attrMap.get("class").split(" ");

				for (String eClass : elemClasses) {

					StyleSpecText classTextStyles = styleMap.getTextStyleSpecFor(qName, eClass);
					if (classTextStyles != null) {
						if (classTextStyles.isBold()) {
							currentITextStyle |= Font.BOLD;
						}
						if (classTextStyles.isItalic()) {
							currentITextStyle |= Font.ITALIC;
						}
						sE.applyTextStyles(classTextStyles);
					}

				}
			}
			if (attrMap.get("style") != null) {
				// TODO this needs more thought, and careful tracking of which tags are still open, etc.
				//String styleSource = attrMap.get("style");
				//CssStyleMap styleTagStyles = cssParser.getStylesFromStyleTag(styleSource);
				// ...
			}

			previousTag = currentTag;
			currentTag = qName;
			if (document.isOpen()) {
				if (XhtmlTags.NEWLINE.equals(qName)) {
					if (stack.size()>0) {
						TextElementArray currentTEA = (TextElementArray) stack.peek();
						currentTEA.add(Chunk.NEWLINE);
					} else if (specialParagraph != null) {
						specialParagraph.add(Chunk.NEWLINE);
					}
				}

				updateStack();

				String xmlElementId = attrMap.get("id");

				if (XhtmlTags.ANCHOR.equals(qName)) {
					//concession to nonconformists...
					if(xmlElementId == null) {
						xmlElementId = attrMap.get("name");
					}
					Anchor anchor = textFactory.newAnchor();
					String ref = attrMap.get(XhtmlTags.REFERENCE);

					if (ref != null) {
						int aNameStartIdx = ref.lastIndexOf("#") + 1;
						ref = ref.substring(aNameStartIdx);
						anchor.setReference(ref);
					}
					if (xmlElementId != null) {
						anchor.setName(xmlElementId);
					}
					stack.push(anchor);
				} 
				else {
					if (xmlElementId != null) {
						flushStack();
						Anchor dest = textFactory.newAnchor();
						dest.setName(xmlElementId);
						stack.push(dest);
						flushStack();
					}
					for (int i = 0; i < 6; i++) {
						if (XhtmlTags.H[i].equals(qName)) {
							flushStack();
							freshParagraph = true;
							currentITextStyle |= Font.BOLD;
							specialParagraph = textFactory.newHeadline(i+1);
							return;
						}
					}
					if ("blockquote".equals(qName)) {
						flushStack();
						freshParagraph = true;
						Paragraph p = textFactory.newParagraph();
						p.setIndentationLeft(50);
						p.setIndentationRight(20);
						p.setAlignment(defaultAlignment);
						stack.push(p);
					} else if (XhtmlTags.PARAGRAPH.equals(qName)) {
						flushStack();
						freshParagraph = true;
						Paragraph p = textFactory.newParagraph();
						stack.push(p);
					} else if (XhtmlTags.DIV.equals(qName)) {
						if (stack.size() > 0 && stack.peek().getChunks().size()>0) {
							flushStack();
						}
						if (stack.size() == 0) {
							stack.push(textFactory.newParagraph());
							freshParagraph = true;
						}
					} else if (XhtmlTags.PRE.equals(qName)) {
						flushStack();
						freshParagraph = true;
						Paragraph p = textFactory.newParagraphPre();
						stack.push(p);
					} else if (XhtmlTags.ORDEREDLIST.equals(qName)) {
						List oList = new List(List.ORDERED, 10); 
						stack.push(oList);
					} else if (XhtmlTags.UNORDEREDLIST.equals(qName)) {
						List uList = new List(List.UNORDERED, 10);
						stack.push(uList);
					} else if (XhtmlTags.LISTITEM.equals(qName)) {
						freshParagraph = true;
						ListItem listItem = new ListItem();
						stack.push(listItem);
					} else if (XhtmlTags.IMAGE.equals(qName)) {
						handleImage(attributes);
					} else if (XhtmlTags.LINK.equals(qName)) {
						// if it's a stylesheet, parse it & update current-style
						if (
								"stylesheet".equals(attrMap.get("rel")) &&
								"text/css".equals(attrMap.get("type")) &&
								attrMap.get("href") != null
						) {
							String cssHref = xhtmlDir.getAbsoluteFile().toURI().toString() + attrMap.get("href");
							CssStyleMap stylesFromLink = cssParser.getStylesFromFileURI(cssHref);
							if (stylesFromLink != null) {
								styleMap.updateWith(stylesFromLink);
							}
						}
					} else if (XhtmlTags.STYLE.equals(qName)) {
						inStyleTag = true;
					} else if (XhtmlTags.EM.equals(qName) || "I".equals(qName.toUpperCase())) {
						currentITextStyle |= Font.ITALIC;
					} else if (XhtmlTags.STRONG.equals(currentTag) || "B".equals(qName.toUpperCase())) {
						currentITextStyle |= Font.BOLD;
					}

				}

			} else if (XhtmlTags.BODY.equals(qName)) {
				document.open();
				freshParagraph = true;
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
		//printlnerr("leaving startElement " + localName + "; stack: " + stackStatus());
	}

	@Override
	public void endElement(String uri, String localName, String qName) {
		/*
		SaxElement closedElement =
			saxElementStack.pop();
		printlnerr("POP :" + saxElementStack.pop());
		 */
		/*
		if("li".equals(qName)) {
			System.err.print("/" + qName + " ");
		}
		
		if("ol".equals(qName) || "ul".equals(qName)) {
			System.err.println("/" + qName + " ");
		}
		*/
		//printlnerr("entering endElement " + localName + "; stack: " + stackStatus());
		try {
			//printlnerr("</" + qName + ">");
			if (document.isOpen()) {
				updateStack();
				for (int i = 0; i < 6; i++) {
					if (XhtmlTags.H[i].equals(qName)) {
						currentITextStyle ^= Font.BOLD;
						if(specialParagraph != null) {
							stack.push(specialParagraph);
						}
						flushStack();
						specialParagraph = null;
						return;
					}
				}
				if (
						XhtmlTags.BLOCKQUOTE.equals(qName)
						|| XhtmlTags.ORDEREDLIST.equals(qName)
						|| XhtmlTags.UNORDEREDLIST.equals(qName)
						|| XhtmlTags.PRE.equals(qName)
						|| XhtmlTags.PARAGRAPH.equals(qName)
						|| XhtmlTags.DIV.equals(qName)) {
					flushStack();
				} else if (XhtmlTags.TT.equals(qName)) {
					updateStack();
				} else if (XhtmlTags.LISTITEM.equals(qName)) {
					ListItem listItem = null;
					List list = null;
					try {
						Element stackTop = (Element) stack.pop();
						try {
							listItem = (ListItem) stackTop;
						} catch (ClassCastException cce) {
							stack.push(stackTop);
						}
						try {
							Element stackTop2 = (Element) stack.pop();
							try {
							list = (List) stackTop2;
							} catch (ClassCastException cce2) {
								stack.push(stackTop2);
							}
						}
						catch (EmptyStackException e) {
							//printlnerr("*** EMPTY STACK (List)");
						}
					} catch (EmptyStackException e) {
						//printlnerr("*** EMPTY STACK (ListItem)");
					} finally {
						if (listItem != null && list != null)
							list.add(listItem);
						if (list != null)
							stack.push(list);
					}
				} else if (XhtmlTags.ANCHOR.equals(qName)) {
					Anchor anchor = null;
					Element stackTop = null;
					try {
						stackTop = (Element) stack.pop();
						try {
						anchor = (Anchor) stackTop;
						} catch (ClassCastException cce) {
							if(stackTop != null)
								stack.push(stackTop);
						}
						TextElementArray previous = (TextElementArray) stack
						.pop();
						previous.add(anchor);
						stack.push(previous);
					} catch (EmptyStackException es) {
						if (anchor != null)
							document.add(anchor);
					} 
				} else if (XhtmlTags.HTML.equals(qName)) {
					flushStack();
					if (this.controlOpenClose) {
						document.close();
					}
				} else if (XhtmlTags.EM.equals(qName) || "I".equals(qName.toUpperCase())) {
					currentITextStyle ^= Font.ITALIC;
				} else if (XhtmlTags.STRONG.equals(currentTag) || "B".equals(qName.toUpperCase())) {
					currentITextStyle ^= Font.BOLD;
				} else if (XhtmlTags.STYLE.equals(qName)) {
					if (styleTagContents != null) {
						CssStyleMap stylesFromHeadTag = cssParser.getStylesFromStyleTag(styleTagContents.toString());
						if (stylesFromHeadTag != null) {
							styleMap.updateWith(stylesFromHeadTag);
						}

						styleTagContents = null;
					}
					inStyleTag = false;
				}

			}

			//printlnerr("leaving endElement " + localName + "; stack: " + stackStatus());
		} catch (Exception e) {
			e.printStackTrace();
		}

		if (saxElementStack.size()>0) {

			StyleSpecText revertStyles = saxElementStack.peek().textStyles;
			if (revertStyles != null) {
				currentITextStyle = Font.NORMAL;
				if (revertStyles.isBold()) {
					currentITextStyle |= Font.BOLD;
				}
				if (revertStyles.isItalic()) {
					currentITextStyle |= Font.ITALIC;
				}
			}

		}

	}

	/**
	 * Flushes the stack, adding all objects in it to the document.
	 */
	private void flushStack() {
		//printlnerr("entering flushStack; stack: " + stackStatus());
		try {
			//printlnerr("*** FLUSH COMMENCE ***");
			while (stack.size() > 0) {
				Element element = null;
				try {
					element = (Element) stack.pop();
					if (!(element instanceof TextElementArray)) {
						System.err.print("");
						System.err.println("Orphaned element: [" + element.toString() + "]");
					}
					TextElementArray previous = (TextElementArray) stack.pop();
					if (previous != null) {
						previous.add(element);
						stack.push(previous);
					} else {
						stack.push(element);
					}

				} catch (EmptyStackException es) {
					if (element != null) {
						document.add(element);
						if(element instanceof TextElementArray) {
							checkTextElementArrayForTocAnchors(
									document, (TextElementArray) element);
						}
					}
				}
			}
			//printlnerr("*** FLUSH ***");
		} catch (Exception e) {
			e.printStackTrace();
			printerr("");
		}
	}

	private void checkTextElementArrayForTocAnchors(Document doc,
			TextElementArray tea) throws DocumentException {
		if(tea instanceof Anchor) {
			Anchor a = (Anchor) tea;
			addAnchorToBookmarks(a);
		}
		else {
			if(tea.type() == Element.PARAGRAPH) {
				Paragraph p = (Paragraph) tea;
				for(int i = 0; i < p.size(); i++) {
					Object o = p.get(i);
					if(o instanceof TextElementArray) {
						checkTextElementArrayForTocAnchors(doc, (TextElementArray) o);
					}
				}
			}
		}
	}

	private void addToBookmarks(String currentFile, String aName) { // curFileCP, aName
		// TODO right now the PDF TOC is flat. The following will have to be 
		//      revised to support multilevel nesting.
		//      Rather than invoking the PdfOutline constructor directly,
		//      create and use a class or set of classes that basically
		//      mirror the Ncx NavPoint tree but with nodes that carry the
		//      generated PdfOutline object along with the NavPoint
		try {
			StringBuilder lookupSB = new StringBuilder();
			String currentFileBaseName = new File(currentFile).getName();
			String currentFileEpubRooted = null;
			for(String spineRef : sourceEpub.getOpf().spineHrefs()) {
				if(spineRef.endsWith(currentFileBaseName)) {
					currentFileEpubRooted = spineRef;
					break;
				}
			}
			if(currentFileEpubRooted == null) {
				//throw new Exception();
			}
			lookupSB.append(currentFileEpubRooted);
			if(aName != null && !aName.isEmpty()) {
				lookupSB.append('#');
				lookupSB.append(aName);
			}
			String lookup = lookupSB.toString();
			NavPoint nP = sourceEpub.getNcx().findNavPoint(lookup);
			if(nP != null) {
				//System.err.println("addToBookmarks " + currentFile + "#" + aName);
				PdfDestination here = new PdfDestination(PdfDestination.FIT);
				new PdfOutline(bookmarkRoot, here, nP.getNavLabelText());
			}
			else {
				//throw new Exception("NCX lookup fail: " + lookup);
			}    		
		}catch(Exception e) {
			//System.err.println("Failed to update PDF bookmarks with Anchor " + aName + ": " + e.getMessage());
		}
	}

	private void addAnchorToBookmarks(Anchor a) {
		if(sourceEpub != null && bookmarkRoot != null && a.getReference() == null && a.getName() != null) {
			String aName = a.getName();
			addToBookmarks(currentFile, aName);
		}
	}

	/**
	 * If the current Chunk is not null, its constituents are forwarded to the stack and it is then made
	 * null.
	 */
	private void updateStack() {
		//printlnerr("entering updateStack; stack: " + stackStatus());
		String[] noNewSpaceTags = {"a","span","em","strong","small","br"};
		java.util.List<String> noNewSpaceTagsColl = new ArrayList<String>();
		for (String tag : noNewSpaceTags) {
			noNewSpaceTagsColl.add(tag);
		}

		if (currentChunk != null) {
			TextElementArray current;
			try {
				current = (TextElementArray) stack.pop();
				if ((!(current instanceof Paragraph)
						|| !((Paragraph) current).isEmpty())
						&&
						!(noNewSpaceTagsColl.contains(currentTag))) {
					//printlnerr("*** CHUNK { } (auto)");
					current.add(new Chunk(" "));
				}
			} catch (EmptyStackException ese) {
				current = textFactory.newParagraph();
			}
			//printlnerr("*** CHUNK {" + currentChunk.getContent() + "}");
			current.add(currentChunk);
			stack.push(current);
			currentChunk = null;
		}
		//printlnerr("leaving updateStack; stack: " + stackStatus());
	}

	/**
	 * Handles the attributes of an IMG tag.
	 *
	 * @param attributes
	 * @throws IOException
	 * @throws MalformedURLException
	 * @throws DocumentException
	 */
	private void handleImage(Attributes attributes)
	throws MalformedURLException, IOException, DocumentException {
		//printlnerr("(image)");
		String url = attributes.getValue(XhtmlTags.URL);
		String alt = attributes.getValue(XhtmlTags.ALT);
		if (url == null)
			return;
		Image img = null;
		try {
			File imgFile = new File(xhtmlDir,url);
			String imgPath = imgFile.getCanonicalPath();
			img = Image.getInstance(imgPath);
			if (alt != null) {
				img.setAlt(alt);
			}
		} catch (Exception e) {
			System.err.println("epub2pdf: problem adding image: " + e.getMessage());
			return;
		}
		String property;
		property = attributes.getValue(XhtmlTags.BORDERWIDTH);
		if (property != null) {
			int border = Integer.parseInt(property);
			if (border == 0) {
				img.setBorder(Image.NO_BORDER);
			} else {
				img.setBorder(Image.BOX);
				img.setBorderWidth(border);
			}
		}
		property = attributes.getValue(XhtmlTags.ALIGN);
		if (property != null) {
			int align = Image.DEFAULT;
			if (ElementTags.ALIGN_LEFT.equalsIgnoreCase(property))
				align = Image.LEFT;
			else if (ElementTags.ALIGN_RIGHT.equalsIgnoreCase(property))
				align = Image.RIGHT;
			else if (ElementTags.ALIGN_MIDDLE.equalsIgnoreCase(property))
				align = Image.MIDDLE;
			img.setAlignment(align | Image.TEXTWRAP);
		} else {
			img.setAlignment(Image.MIDDLE);
		}

		Rectangle pageRect = document.getPageSize();
		float imgMaxWidth = pageRect.getWidth() - 9;
		float imgMaxHeight = pageRect.getHeight() - 6;

		float imgOrigWidth = img.getWidth();
		float imgOrigHeight = img.getHeight();

		if (imgOrigHeight > imgMaxHeight || imgOrigWidth > imgMaxWidth) {
			img.scaleToFit(imgMaxWidth, imgMaxHeight);
		}

		document.add(img);

	}

	public XhtmlHandler(String xhtml, Document docInProgress) throws MalformedURLException, IOException, SAXException {
		super(docInProgress);
		currentFile = xhtml;
		this.controlOpenClose = false;
		document = docInProgress;

		parseXhtml(xhtml);
	}

	void parseXhtml(String xhtml) throws MalformedURLException, IOException, SAXException {

		File xhtmlFile = new File(xhtml);
		
		System.err.println("Processing " + xhtmlFile.getName());
		
		xhtmlDir = xhtmlFile.getParentFile();

		CatalogManager.getStaticManager().setIgnoreMissingProperties(true);
		ResolvingXMLReader reader = new ResolvingXMLReader();
		Catalog catalog = reader.getCatalog();
		catalog.parseCatalog("etc/xhtml/catalog.xml");
		reader.setContentHandler(this);
		InputSource iS = new InputSource(new FileInputStream(xhtml));
		reader.parse(iS);
	}

	public static void process(String xhtml, Document doc) {
		try {
			new XhtmlHandler(xhtml,doc);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}


	@SuppressWarnings("unchecked")
	static String elementContentsAbridged(Element el) {
		String content = "";
		ArrayList chunkList = el.getChunks();
		for (Object o : chunkList) {
			Chunk ch = (Chunk) o;
			content += ch.getContent();
		}
		int len = content.length();
		if (len>25) {
			StringBuilder sB = new StringBuilder();
			sB.append(content.substring(0, 10));
			sB.append(" ... ");
			sB.append(content.substring(len-10));
			return sB.toString();
		} else {
			return content;
		}
	}
	/*
    public static void setDefaultFont(String fontName) {

    }
	 */

	private static class SaxElement {
		final String qName;
		final int id;
		final String idAttr;
		StyleSpecText textStyles;

		SaxElement(String q, int i, String idA) {
			qName = q;
			id = i;
			idAttr = idA;	
		}

		void applyTextStyles(StyleSpecText sst) {
			textStyles = new StyleSpecText(sst);
		}

		@Override
		public String toString() {
			return qName + "_" + id;
		}
	}

	public static void setDefaultPageSize(float widthPt, float heightPt) {
		pageSize = new RectangleReadOnly(widthPt, heightPt);
	}
	public static void setDefaultAlignment(int itextAlignmentCode) {
		defaultAlignment = itextAlignmentCode;
		TextFactory.setDefaultITAlignment(ITAlignment.getValueForInt(itextAlignmentCode));
	}

	public static void setSourceEpub(Epub epubIn) {
		sourceEpub = epubIn;
	}
	public static void setBookmarkRoot(PdfOutline outline) {
		bookmarkRoot = outline;
	}

}