/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003-2005 University of Maryland
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
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;

import org.apache.bcel.Repository;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.classfile.Signature;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.ClassAnnotation;
import edu.umd.cs.findbugs.Lookup;
import edu.umd.cs.findbugs.MethodAnnotation;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.StatelessDetector;
import edu.umd.cs.findbugs.TypeAnnotation;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.ba.EqualsKindSummary;
import edu.umd.cs.findbugs.ba.XClass;
import edu.umd.cs.findbugs.ba.XMethod;
import edu.umd.cs.findbugs.ba.ch.Subtypes2;
import edu.umd.cs.findbugs.bcel.OpcodeStackDetector;
import edu.umd.cs.findbugs.classfile.ClassDescriptor;
import edu.umd.cs.findbugs.classfile.DescriptorFactory;
import edu.umd.cs.findbugs.internalAnnotations.DottedClassName;
import edu.umd.cs.findbugs.util.ClassName;
import edu.umd.cs.findbugs.visitclass.PreorderVisitor;

public class FindHEmismatch extends OpcodeStackDetector implements StatelessDetector {
    boolean hasFields = false;

    boolean visibleOutsidePackage = false;

    boolean hasHashCode = false;

    boolean hasEqualsObject = false;

    boolean hashCodeIsAbstract = false;

    boolean equalsObjectIsAbstract = false;

    boolean equalsMethodIsInstanceOfEquals = false;

    boolean equalsReimplementesObjectEquals = false;

    boolean hasCompareToObject = false;

    boolean hasCompareToBridgeMethod = false;

    boolean hasEqualsSelf = false;

    boolean hasEqualsOther = false;

    boolean hasCompareToSelf = false;

    boolean extendsObject = false;

    MethodAnnotation equalsMethod = null;

    MethodAnnotation equalsOtherMethod = null;

    ClassDescriptor equalsOtherClass = null;

    MethodAnnotation compareToMethod = null;

    MethodAnnotation compareToObjectMethod = null;

    MethodAnnotation compareToSelfMethod = null;

    MethodAnnotation hashCodeMethod = null;

    HashSet<String> nonHashableClasses = new HashSet<String>();

    public boolean isHashableClassName(String dottedClassName) {
        return !nonHashableClasses.contains(dottedClassName);
    }

    Map<String, BugInstance> potentialBugs = new HashMap<String, BugInstance>();

    private BugReporter bugReporter;

    public FindHEmismatch(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
    }

    @Override
    public void visitAfter(JavaClass obj) {
        if (!obj.isClass())
            return;
        if (getDottedClassName().equals("java.lang.Object"))
            return;
        int accessFlags = obj.getAccessFlags();
        if ((accessFlags & ACC_INTERFACE) != 0)
            return;
        visibleOutsidePackage = obj.isPublic() || obj.isProtected();

        String whereEqual = getDottedClassName();
        boolean inheritedHashCodeIsFinal = false;
        boolean inheritedEqualsIsFinal = false;
        boolean inheritedEqualsIsAbstract = false;
        boolean inheritedEqualsFromAbstractClass = false;
        XMethod inheritedEquals = null;
        if (!hasEqualsObject) {
            XClass we = Lookup.findImplementor(getXClass(), "equals", "(Ljava/lang/Object;)Z", false, bugReporter);
            if (we == null || we.equals(getXClass())) {
                whereEqual = "java.lang.Object";
            } else {
                inheritedEqualsFromAbstractClass = we.isAbstract();
                whereEqual = we.getClassDescriptor().getDottedClassName();
                inheritedEquals = we.findMethod("equals", "(Ljava/lang/Object;)Z", false);
                if (inheritedEquals != null) {
                    inheritedEqualsIsFinal = inheritedEquals.isFinal();
                    inheritedEqualsIsAbstract = inheritedEquals.isAbstract();
                }
            }
        }
        boolean usesDefaultEquals = whereEqual.equals("java.lang.Object");
        String whereHashCode = getDottedClassName();
        if (!hasHashCode) {
            XClass wh = Lookup.findSuperImplementor(getXClass(), "hashCode", "()I", false, bugReporter);
            if (wh == null) {
                whereHashCode = "java.lang.Object";
            } else {
                whereHashCode = wh.getClassDescriptor().getDottedClassName();
                XMethod m = wh.findMethod("hashCode", "()I", false);
                if (m != null && m.isFinal())
                    inheritedHashCodeIsFinal = true;
            }
        }
//        boolean usesDefaultHashCode = whereHashCode.equals("java.lang.Object");
//        if (false && (usesDefaultEquals || usesDefaultHashCode)) {
//            try {
//                if (Repository.implementationOf(obj, "java/util/Set") || Repository.implementationOf(obj, "java/util/List")
//                        || Repository.implementationOf(obj, "java/util/Map")) {
//                    // System.out.println(getDottedClassName() + " uses default
//                    // hashCode or equals");
//                }
//            } catch (ClassNotFoundException e) {
//                // e.printStackTrace();
//            }
//        }
//
//        if (!hasEqualsObject && !hasEqualsSelf && hasEqualsOther) {
//            BugInstance bug = new BugInstance(this, usesDefaultEquals ? "EQ_OTHER_USE_OBJECT" : "EQ_OTHER_NO_OBJECT",
//                    NORMAL_PRIORITY).addClass(this).addMethod(equalsOtherMethod).addClass(equalsOtherClass);
//            bugReporter.reportBug(bug);
//        }
//        if (!hasEqualsObject && hasEqualsSelf) {
//
//            if (usesDefaultEquals) {
//                int priority = HIGH_PRIORITY;
//                if (usesDefaultHashCode || obj.isAbstract())
//                    priority++;
//                if (!visibleOutsidePackage)
//                    priority++;
//                String bugPattern = "EQ_SELF_USE_OBJECT";
//
//                BugInstance bug = new BugInstance(this, bugPattern, priority).addClass(getDottedClassName());
//                if (equalsMethod != null)
//                    bug.addMethod(equalsMethod);
//                bugReporter.reportBug(bug);
//            } else {
//                int priority = NORMAL_PRIORITY;
//                if (hasFields)
//                    priority--;
//                if (obj.isAbstract())
//                    priority++;
//                String bugPattern = "EQ_SELF_NO_OBJECT";
//                String superclassName = obj.getSuperclassName();
//                if (superclassName.equals("java.lang.Enum")) {
//                    bugPattern = "EQ_DONT_DEFINE_EQUALS_FOR_ENUM";
//                    priority = HIGH_PRIORITY;
//                }
//                BugInstance bug = new BugInstance(this, bugPattern, priority).addClass(getDottedClassName());
//                if (equalsMethod != null)
//                    bug.addMethod(equalsMethod);
//                bugReporter.reportBug(bug);
//            }
//        }
//
//        // System.out.println("Class " + getDottedClassName());
//        // System.out.println("usesDefaultEquals: " + usesDefaultEquals);
//        // System.out.println("hasHashCode: : " + hasHashCode);
//        // System.out.println("usesDefaultHashCode: " + usesDefaultHashCode);
//        // System.out.println("hasEquals: : " + hasEqualsObject);
//        // System.out.println("hasCompareToObject: : " + hasCompareToObject);
//        // System.out.println("hasCompareToSelf: : " + hasCompareToSelf);
//
//        if ((hasCompareToObject || hasCompareToSelf) && usesDefaultEquals) {
//            BugInstance bug = new BugInstance(this, "EQ_COMPARETO_USE_OBJECT_EQUALS", obj.isAbstract() ? Priorities.LOW_PRIORITY
//                    : Priorities.NORMAL_PRIORITY).addClass(this);
//            if (compareToSelfMethod != null)
//                bug.addMethod(compareToSelfMethod);
//            else
//                bug.addMethod(compareToObjectMethod);
//            bugReporter.reportBug(bug);
//        }
//        if (!hasCompareToObject && !hasCompareToBridgeMethod && hasCompareToSelf) {
//            if (!extendsObject)
//                bugReporter.reportBug(new BugInstance(this, "CO_SELF_NO_OBJECT", NORMAL_PRIORITY).addClass(getDottedClassName())
//                        .addMethod(compareToMethod));
//        }
//
//        // if (!hasFields) return;
//        if (hasHashCode && !hashCodeIsAbstract && !(hasEqualsObject && !equalsReimplementesObjectEquals || hasEqualsSelf)) {
//            int priority = LOW_PRIORITY;
//            if (usesDefaultEquals)
//                bugReporter.reportBug(new BugInstance(this, "HE_HASHCODE_USE_OBJECT_EQUALS", priority).addClass(
//                        getDottedClassName()).addMethod(hashCodeMethod));
//            else if (!inheritedEqualsIsFinal)
//                bugReporter.reportBug(new BugInstance(this, "HE_HASHCODE_NO_EQUALS", priority).addClass(getDottedClassName())
//                        .addMethod(hashCodeMethod));
//        }
//        if (equalsObjectIsAbstract) {
//            // no errors reported
//        } else if (!hasHashCode && (hasEqualsObject || hasEqualsSelf)) {
//            EqualsKindSummary.KindOfEquals equalsKind = AnalysisContext.currentAnalysisContext().getEqualsKindSummary()
//                    .get(new ClassAnnotation(obj.getClassName()));
//            if (equalsKind == EqualsKindSummary.KindOfEquals.ALWAYS_FALSE)
//                return;
//            if (usesDefaultHashCode) {
//                int priority = HIGH_PRIORITY;
//                if (equalsMethodIsInstanceOfEquals)
//                    priority += 2;
//                else if (obj.isAbstract() || !hasEqualsObject)
//                    priority++;
//                if (priority == HIGH_PRIORITY)
//                    nonHashableClasses.add(getDottedClassName());
//                if (!visibleOutsidePackage) {
//                    priority++;
//                }
//                BugInstance bug = new BugInstance(this, "HE_EQUALS_USE_HASHCODE", priority).addClass(getDottedClassName());
//                if (equalsMethod != null)
//                    bug.addMethod(equalsMethod);
//                bugReporter.reportBug(bug);
//            } else if (!inheritedHashCodeIsFinal && !whereHashCode.startsWith("java.util.Abstract")) {
//                int priority = LOW_PRIORITY;
//
//                if (hasEqualsObject && inheritedEqualsIsAbstract)
//                    priority++;
//                if (hasFields)
//                    priority--;
//                if (equalsMethodIsInstanceOfEquals || !hasEqualsObject)
//                    priority += 2;
//                else if (obj.isAbstract())
//                    priority++;
//                BugInstance bug = new BugInstance(this, "HE_EQUALS_NO_HASHCODE", priority).addClass(getDottedClassName());
//                if (equalsMethod != null)
//                    bug.addMethod(equalsMethod);
//                bugReporter.reportBug(bug);
//            }
//        }
//        if (!hasHashCode && !hasEqualsObject && !hasEqualsSelf && !usesDefaultEquals && usesDefaultHashCode && !obj.isAbstract()
//                && inheritedEqualsFromAbstractClass) {
//            BugInstance bug = new BugInstance(this, "HE_INHERITS_EQUALS_USE_HASHCODE", NORMAL_PRIORITY)
//                    .addClass(getDottedClassName());
//            if (equalsMethod != null)
//                bug.addMethod(equalsMethod);
//            bugReporter.reportBug(bug);
//        }
//        if (!hasEqualsObject && !hasEqualsSelf && !usesDefaultEquals && !obj.isAbstract() && hasFields && inheritedEquals != null
//                && !inheritedEqualsIsFinal && !inheritedEqualsFromAbstractClass
//                && !inheritedEquals.getClassDescriptor().getSimpleName().contains("Abstract")
//                && !inheritedEquals.getClassDescriptor().getClassName().equals("java/lang/Enum")) {
//
//            BugInstance bug = new BugInstance(this, "EQ_DOESNT_OVERRIDE_EQUALS", NORMAL_PRIORITY).addClass(this)
//                    .addMethod(inheritedEquals).describe(MethodAnnotation.METHOD_DID_YOU_MEAN_TO_OVERRIDE);
//            bugReporter.reportBug(bug);
//        }
    }

    @Override
    public void visit(JavaClass obj) {
        extendsObject = getDottedSuperclassName().equals("java.lang.Object");
        hasFields = false;
        hasHashCode = false;
        hasCompareToObject = false;
        hasCompareToBridgeMethod = false;
        hasCompareToSelf = false;
        hasEqualsObject = false;
        hasEqualsSelf = false;
        hasEqualsOther = false;
        hashCodeIsAbstract = false;
        equalsObjectIsAbstract = false;
        equalsMethodIsInstanceOfEquals = false;
        equalsMethod = null;
        equalsOtherMethod = null;
        compareToMethod = null;
        compareToSelfMethod = null;
        compareToObjectMethod = null;
        hashCodeMethod = null;
        equalsOtherClass = null;
        isApplicationClass = AnalysisContext.currentAnalysisContext().isApplicationClass(obj);
    }

    @Override
    public boolean shouldVisitCode(Code obj) {
        if (isApplicationClass)
            return true;
        String name = getMethod().getName();
        if (name.equals("hashCode") || name.equals("equals"))
            return true;
        return false;

    }
    boolean isApplicationClass;

    public static int opcode(byte code[], int offset) {
        return code[offset] & 0xff;
    }

    @Override
    public void visit(Field obj) {
        int accessFlags = obj.getAccessFlags();
        if ((accessFlags & ACC_STATIC) != 0)
            return;
        if (!obj.getName().startsWith("this$") && !obj.isSynthetic() && !obj.isTransient())
            hasFields = true;
    }

    static final Pattern predicateOverAnInstance = Pattern.compile("\\(L([^;]+);\\)Z");

    @Override
    public void visit(Method obj) {

        int accessFlags = obj.getAccessFlags();
        if ((accessFlags & ACC_STATIC) != 0)
            return;
        String name = obj.getName();
        String sig = obj.getSignature();
        if ((accessFlags & ACC_ABSTRACT) != 0) {
            if (name.equals("equals") && sig.equals("(L" + getClassName() + ";)Z")) {
                bugReporter.reportBug(new BugInstance(this, "EQ_ABSTRACT_SELF", LOW_PRIORITY).addClass(getDottedClassName()));
                return;
            } /* else if (name.equals("compareTo") && sig.equals("(L" + getClassName() + ";)I")) {
                bugReporter.reportBug(new BugInstance(this, "CO_ABSTRACT_SELF", LOW_PRIORITY).addClass(getDottedClassName()));
                return;
            }*/
        }
        /*
        boolean sigIsObject = sig.equals("(Ljava/lang/Object;)Z");
        if (name.equals("hashCode") && sig.equals("()I")) {
            hasHashCode = true;
            if (obj.isAbstract())
                hashCodeIsAbstract = true;
            hashCodeMethod = MethodAnnotation.fromVisitedMethod(this);
            // System.out.println("Found hashCode for " + betterClassName);
        } else if (obj.isPublic() && name.equals("equals")) {
            Matcher m = predicateOverAnInstance.matcher(sig);
            if (m.matches()) {
                if (sigIsObject) {
                    equalsMethod = MethodAnnotation.fromVisitedMethod(this);
                    hasEqualsObject = true;
                    if (obj.isAbstract())
                        equalsObjectIsAbstract = true;
                    else if (!obj.isNative()) {
                        Code code = obj.getCode();
                        byte[] codeBytes = code.getCode();
                        if (codeBytes.length == 9) {
                            int op0 = opcode(codeBytes, 0);
                            int op1 = opcode(codeBytes, 1);
                            int op2 = opcode(codeBytes, 2);
                            int op5 = opcode(codeBytes, 5);
                            int op6 = opcode(codeBytes, 6);
                            int op7 = opcode(codeBytes, 7);
                            int op8 = opcode(codeBytes, 8);
                            if ((op0 == ALOAD_0 && op1 == ALOAD_1 || op0 == ALOAD_1 && op1 == ALOAD_0)
                                    && (op2 == IF_ACMPEQ || op2 == IF_ACMPNE) && (op5 == ICONST_0 || op5 == ICONST_1)
                                    && op6 == IRETURN && (op7 == ICONST_0 || op7 == ICONST_1) && op8 == IRETURN)
                                equalsMethodIsInstanceOfEquals = true;
                        } else if (codeBytes.length == 11) {
                            int op0 = opcode(codeBytes, 0);
                            int op1 = opcode(codeBytes, 1);
                            int op2 = opcode(codeBytes, 2);
                            int op5 = opcode(codeBytes, 5);
                            int op6 = opcode(codeBytes, 6);
                            int op9 = opcode(codeBytes, 9);
                            int op10 = opcode(codeBytes, 10);
                            if ((op0 == ALOAD_0 && op1 == ALOAD_1 || op0 == ALOAD_1 && op1 == ALOAD_0)
                                    && (op2 == IF_ACMPEQ || op2 == IF_ACMPNE) && (op5 == ICONST_0 || op5 == ICONST_1)
                                    && op6 == GOTO && (op9 == ICONST_0 || op9 == ICONST_1) && op10 == IRETURN)
                                equalsMethodIsInstanceOfEquals = true;

                        } else if ((codeBytes.length == 5 && (codeBytes[1] & 0xff) == INSTANCEOF)
                                || (codeBytes.length == 15 && (codeBytes[1] & 0xff) == INSTANCEOF && (codeBytes[11] & 0xff) == INVOKESPECIAL)) {
                            equalsMethodIsInstanceOfEquals = true;
                        }
                    }
                } else if (sig.equals("(L" + getClassName() + ";)Z")) {
                    hasEqualsSelf = true;
                    if (equalsMethod == null)
                        equalsMethod = MethodAnnotation.fromVisitedMethod(this);
                } else {
                    String arg = m.group(1);
                    if (getSuperclassName().equals(arg)) {
                        JavaClass findSuperImplementor = Lookup.findSuperDefiner(getThisClass(), name, sig, bugReporter);
                        if (findSuperImplementor == null) {
                            hasEqualsOther = true;
                            equalsOtherMethod = MethodAnnotation.fromVisitedMethod(this);
                            equalsOtherClass = DescriptorFactory.createClassDescriptor(arg);
                        }
                    }

                }
            }
        } else if (name.equals("compareTo") && sig.endsWith(")I") && !obj.isStatic()) {
            MethodAnnotation tmp = MethodAnnotation.fromVisitedMethod(this);
            if (obj.isSynthetic())
                hasCompareToBridgeMethod = true;
            if (sig.equals("(Ljava/lang/Object;)I")) {
                hasCompareToObject = true;
                compareToObjectMethod = compareToMethod = tmp;
            } else if (sig.equals("(L" + getClassName() + ";)I")) {
                hasCompareToSelf = true;
                compareToSelfMethod = compareToMethod = tmp;
            }
        }
        */
    }

    Method findMethod(JavaClass clazz, String name, String sig) {
        Method[] m = clazz.getMethods();
        for (Method aM : m)
            if (aM.getName().equals(name) && aM.getSignature().equals(sig))
                return aM;
        return null;
    }

    @Override
    public void sawOpcode(int seen) {
//        if (seen == INVOKEVIRTUAL || seen == INVOKEINTERFACE) {
//            String className = getClassConstantOperand();
//            if (className.equals("java/util/Map") || className.equals("java/util/HashMap")
//                    || className.equals("java/util/LinkedHashMap") || className.equals("java/util/concurrent/ConcurrentHashMap")
//                    || className.contains("Hash")
//                    && Subtypes2.instanceOf(ClassName.toDottedClassName(className), "java.util.Map")) {
//                if (getNameConstantOperand().equals("put")
//                        && getSigConstantOperand().equals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
//                        && stack.getStackDepth() >= 3)
//                    check(1);
//                else if ((getNameConstantOperand().equals("get") || getNameConstantOperand().equals("remove"))
//                        && getSigConstantOperand().startsWith("(Ljava/lang/Object;)") && stack.getStackDepth() >= 2)
//                    check(0);
//            } else if (className.equals("java/util/Set") || className.equals("java/util/HashSet") || className.contains("Hash")
//                    && Subtypes2.instanceOf(ClassName.toDottedClassName(className), "java.util.Set")) {
//                if (getNameConstantOperand().equals("add") || getNameConstantOperand().equals("contains")
//                        || getNameConstantOperand().equals("remove") && getSigConstantOperand().equals("(Ljava/lang/Object;)Z")
//                        && stack.getStackDepth() >= 2)
//                    check(0);
//            }
//        }
    }

//    private void check(int pos) {
//        OpcodeStack.Item item = stack.getStackItem(pos);
//        JavaClass type = null;
//
//        try {
//            type = item.getJavaClass();
//        } catch (ClassNotFoundException e) {
//            AnalysisContext.reportMissingClass(e);
//        }
//        if (type == null)
//            return;
//        String typeName = type.getClassName();
//        if (typeName.startsWith("java.lang"))
//            return;
//        int priority = NORMAL_PRIORITY;
//
//        OpcodeStack.Item collection = stack.getStackItem(PreorderVisitor.getNumberArguments(getSigConstantOperand()));
//        String collectionSignature = collection.getSignature();
//        if (collectionSignature.indexOf("Tree") >= 0
//                || collectionSignature.indexOf("Sorted") >= 0
//                || collectionSignature.indexOf("SkipList") >= 0 )
//            return;
//
//        if (collectionSignature.indexOf("Hash") >= 0)
//            priority--;
//        if (!AnalysisContext.currentAnalysisContext()/* .getSubtypes() */.isApplicationClass(type))
//            priority++;
//
//        if (type.isAbstract() || type.isInterface())
//            priority++;
//        potentialBugs.put(
//                type.getClassName(),
//                new BugInstance(this, "HE_USE_OF_UNHASHABLE_CLASS", priority).addClassAndMethod(this)
//                        .addTypeOfNamedClass(type.getClassName()).describe(TypeAnnotation.UNHASHABLE_ROLE).addCalledMethod(this)
//                        .addSourceLine(this));
//    }

    static final Pattern mapPattern = Pattern.compile("[^y]HashMap<L([^;<]*);");

    static final Pattern hashTablePattern = Pattern.compile("Hashtable<L([^;<]*);");

    static final Pattern setPattern = Pattern.compile("[^y]HashSet<L([^;<]*);");

    @CheckForNull
    @DottedClassName
    String findHashedClassInSignature(String sig) {
        Matcher m = mapPattern.matcher(sig);
        if (m.find())
            return m.group(1).replace('/', '.');
        m = hashTablePattern.matcher(sig);
        if (m.find())
            return m.group(1).replace('/', '.');

        m = setPattern.matcher(sig);
        if (m.find())
            return m.group(1).replace('/', '.');
        return null;

    }

    @Override
    public void visit(Signature obj) {
        if (!isApplicationClass)
            return;

        String sig = obj.getSignature();
        String className = findHashedClassInSignature(sig);
        if (className == null)
            return;
        if (className.startsWith("java.lang"))
            return;
        JavaClass type = null;

        try {
            type = Repository.lookupClass(className);
        } catch (ClassNotFoundException e) {
            AnalysisContext.reportMissingClass(e);
        }
        if (type == null)
            return;

        int priority = NORMAL_PRIORITY;
        if (sig.indexOf("Hash") >= 0)
            priority--;
        if (type.isAbstract() || type.isInterface())
            priority++;
        if (!AnalysisContext.currentAnalysisContext()/* .getSubtypes() */.isApplicationClass(type))
            priority++;

        BugInstance bug = null;

        if (visitingField())
            bug = new BugInstance(this, "HE_SIGNATURE_DECLARES_HASHING_OF_UNHASHABLE_CLASS", priority).addClass(this)
                    .addVisitedField(this).addTypeOfNamedClass(className).describe(TypeAnnotation.UNHASHABLE_ROLE);
        else if (visitingMethod())
            bug = new BugInstance(this, "HE_SIGNATURE_DECLARES_HASHING_OF_UNHASHABLE_CLASS", priority).addClassAndMethod(this)
                    .addTypeOfNamedClass(className).describe(TypeAnnotation.UNHASHABLE_ROLE);
        else
            bug = new BugInstance(this, "HE_SIGNATURE_DECLARES_HASHING_OF_UNHASHABLE_CLASS", priority).addClass(this)
                    .addClass(this).addTypeOfNamedClass(className).describe(TypeAnnotation.UNHASHABLE_ROLE);
        potentialBugs.put(className, bug);
    }

    @Override
    public void report() {
        for (Map.Entry<String, BugInstance> e : potentialBugs.entrySet()) {
            if (!isHashableClassName(e.getKey())) {
                BugInstance bug = e.getValue();

                bugReporter.reportBug(bug);
            }
        }

    }
}
