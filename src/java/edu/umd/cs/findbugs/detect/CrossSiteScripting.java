/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2004-2006 University of Maryland
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.detect;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.bcel.classfile.Code;

import edu.umd.cs.findbugs.BugAccumulator;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.StringAnnotation;
import edu.umd.cs.findbugs.ba.XMethod;
import edu.umd.cs.findbugs.bcel.OpcodeStackDetector;

public class CrossSiteScripting extends OpcodeStackDetector {

    final BugReporter bugReporter;

    final BugAccumulator accumulator;

    public CrossSiteScripting(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
        this.accumulator = new BugAccumulator(bugReporter);
    }

    Map<String, OpcodeStack.Item> map = new HashMap<String, OpcodeStack.Item>();

    OpcodeStack.Item top = null;

    Pattern xmlSafe = Pattern.compile("\\p{Alnum}+");

    @Override
    public void visit(Code code) {
        isPlainText = false;
        super.visit(code);
        map.clear();
        accumulator.reportAccumulatedBugs();
    }

    private void annotateAndReport(BugInstance bug, OpcodeStack.Item item) {
        assert item.isServletParameterTainted();
        String s = item.getHttpParameterName();
        int pc = item.getInjectionPC();
        if (s != null && xmlSafe.matcher(s).matches())
            bug.addString(s).describe(StringAnnotation.PARAMETER_NAME_ROLE);
        SourceLineAnnotation thisLine = SourceLineAnnotation.fromVisitedInstruction(this);
        if (pc >= 0) {
            SourceLineAnnotation source = SourceLineAnnotation.fromVisitedInstruction(this, pc);
            if (thisLine.getStartLine() != source.getStartLine())
                bug.add(source).describe(SourceLineAnnotation.ROLE_GENERATED_AT);
        }

        bug.addOptionalLocalVariable(this, item);
        accumulator.accumulateBug(bug, this);
    }

    OpcodeStack.Item replaceTop = null;

    boolean isPlainText;

    @Override
    public void sawOpcode(int seen) {
        if (replaceTop != null) {
            stack.replaceTop(replaceTop);
            replaceTop = null;
        }

        OpcodeStack.Item oldTop = top;
        top = null;
        if (seen == INVOKESPECIAL) {
            String calledClassName = getClassConstantOperand();
            String calledMethodName = getNameConstantOperand();
            String calledMethodSig = getSigConstantOperand();

            if (calledClassName.startsWith("java/io/File")
                    && calledMethodSig.equals("(Ljava/lang/String;)V")) {
                OpcodeStack.Item path = stack.getStackItem(0);
                if (isTainted(path)) {
                    annotateAndReport(new BugInstance(this, "TESTING", taintPriority(path))
                          .addClassAndMethod(this).addCalledMethod(this)
                          .addString("Path manipulation"),
                            path);
                }

            }



            if (calledClassName.equals("javax/servlet/http/Cookie") && calledMethodName.equals("<init>")
                    && calledMethodSig.equals("(Ljava/lang/String;Ljava/lang/String;)V")) {
                OpcodeStack.Item value = stack.getStackItem(0);
                OpcodeStack.Item name = stack.getStackItem(1);
                if (value.isServletParameterTainted() || name.isServletParameterTainted()) {
                    int priority = Math.min(taintPriority(value), taintPriority(name));
                    annotateAndReport(new BugInstance(this, "HRS_REQUEST_PARAMETER_TO_COOKIE", priority).addClassAndMethod(this),
                            value.isServletParameterTainted() ? value : name);
                }

            }

        } else if (seen == INVOKEINTERFACE) {
            String calledClassName = getClassConstantOperand();
            String calledMethodName = getNameConstantOperand();
            String calledMethodSig = getSigConstantOperand();
            if (calledClassName.equals("javax/servlet/http/HttpServletResponse") && calledMethodName.equals("setContentType")) {
                OpcodeStack.Item writing = stack.getStackItem(0);
                if ("text/plain".equals(writing.getConstant())) {
                    isPlainText = true;
                }
            } else if (calledClassName.equals("javax/servlet/http/HttpSession") && calledMethodName.equals("setAttribute")) {

                OpcodeStack.Item value = stack.getStackItem(0);
                OpcodeStack.Item name = stack.getStackItem(1);
                Object nameConstant = name.getConstant();
                if (nameConstant instanceof String)
                    map.put((String) nameConstant, value);
            } else if (calledClassName.equals("javax/servlet/http/HttpSession") && calledMethodName.equals("getAttribute")) {
                OpcodeStack.Item name = stack.getStackItem(0);
                Object nameConstant = name.getConstant();
                if (nameConstant instanceof String) {
                    top = map.get(nameConstant);

                    if (isTainted(top)) {
                        replaceTop = top;
                    }
                }
            } else if (calledClassName.equals("javax/servlet/http/HttpServletResponse")
                    && (calledMethodName.startsWith("send") || calledMethodName.endsWith("Header"))
                    && calledMethodSig.endsWith("Ljava/lang/String;)V")) {

                OpcodeStack.Item writing = stack.getStackItem(0);
                if (isTainted(writing)) {
                    if (calledMethodName.equals("sendError"))
                        annotateAndReport(
                                new BugInstance(this, "XSS_REQUEST_PARAMETER_TO_SEND_ERROR", taintPriority(writing))
                                        .addClassAndMethod(this),
                                writing);
                    else
                        annotateAndReport(
                                new BugInstance(this, "HRS_REQUEST_PARAMETER_TO_HTTP_HEADER", taintPriority(writing))
                                        .addClassAndMethod(this),
                                writing);
                }
            }

        } else if (seen == INVOKEVIRTUAL && !isPlainText) {
            String calledClassName = getClassConstantOperand();
            String calledMethodName = getNameConstantOperand();
            String calledMethodSig = getSigConstantOperand();

            if ((calledMethodName.startsWith("print") || calledMethodName.equals("write"))
                    && calledClassName.equals("javax/servlet/jsp/JspWriter")
                    && (calledMethodSig.equals("(Ljava/lang/Object;)V") || calledMethodSig.equals("(Ljava/lang/String;)V"))) {
                OpcodeStack.Item writing = stack.getStackItem(0);
                // System.out.println(SourceLineAnnotation.fromVisitedInstruction(this)
                // + " writing " + writing);
                if (isTainted(writing))
                    annotateAndReport(
                            new BugInstance(this, "XSS_REQUEST_PARAMETER_TO_JSP_WRITER", taintPriority(writing))
                                    .addClassAndMethod(this),
                            writing);
                else if (isTainted(oldTop))
                    annotateAndReport(
                            new BugInstance(this, "XSS_REQUEST_PARAMETER_TO_JSP_WRITER", Priorities.NORMAL_PRIORITY)
                                    .addClassAndMethod(this),
                            oldTop);
            } else if (calledClassName.startsWith("java/io/") && calledClassName.endsWith("Writer")
                    && (calledMethodName.startsWith("print") || calledMethodName.startsWith("write"))
                    && (calledMethodSig.equals("(Ljava/lang/Object;)V") || calledMethodSig.equals("(Ljava/lang/String;)V"))) {
                OpcodeStack.Item writing = stack.getStackItem(0);
                OpcodeStack.Item writingTo = stack.getStackItem(1);
                if (isTainted(writing) && writingTo.isServletWriter())
                    annotateAndReport(
                            new BugInstance(this, "XSS_REQUEST_PARAMETER_TO_SERVLET_WRITER", taintPriority(writing))
                                    .addClassAndMethod(this),
                            writing);
                else if (isTainted(oldTop) && writingTo.isServletWriter())
                    annotateAndReport(
                            new BugInstance(this, "XSS_REQUEST_PARAMETER_TO_SERVLET_WRITER", Priorities.NORMAL_PRIORITY)
                                    .addClassAndMethod(this),
                            writing);

            }
        }
    }

    private boolean isTainted(OpcodeStack.Item writing) {
        if (writing == null)
            return false;
        return writing.isServletParameterTainted();
    }

    private int taintPriority(OpcodeStack.Item writing) {
        if (writing == null)
            return Priorities.NORMAL_PRIORITY;
        XMethod method = writing.getReturnValueOf();
        if (method != null && method.getName().equals("getParameter")
                && method.getClassName().equals("javax.servlet.http.HttpServletRequest"))
            return Priorities.HIGH_PRIORITY;
        return Priorities.NORMAL_PRIORITY;

    }



}
