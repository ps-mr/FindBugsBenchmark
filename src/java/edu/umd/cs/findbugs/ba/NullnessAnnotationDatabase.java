/*
 * FindBugs - Find Bugs in Java programs
 * Copyright (C) 2005, University of Maryland
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

package edu.umd.cs.findbugs.ba;

import javax.annotation.CheckForNull;

import edu.umd.cs.findbugs.classfile.Global;
import edu.umd.cs.findbugs.log.Profiler;

/**
 * @author pugh
 */
public class NullnessAnnotationDatabase extends AnnotationDatabase<NullnessAnnotation> implements INullnessAnnotationDatabase {

    public NullnessAnnotationDatabase() {
        setAddClassOnly(true);
        loadAuxiliaryAnnotations();
        setAddClassOnly(false);
    }

    @Override
    public void loadAuxiliaryAnnotations() {
        DefaultNullnessAnnotations.addDefaultNullnessAnnotations(this);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * edu.umd.cs.findbugs.ba.INullnessAnnotationDatabase#parameterMustBeNonNull
     * (edu.umd.cs.findbugs.ba.XMethod, int)
     */
    public boolean parameterMustBeNonNull(XMethod m, int param) {
        if (param == 0) {
            if (m.getName().equals("equals") && m.getSignature().equals("(Ljava/lang/Object;)Z") && !m.isStatic())
                return false;
            else if (m.getName().equals("main") && m.getSignature().equals("([Ljava/lang/String;)V") && m.isStatic()
                    && m.isPublic())
                return true;
            else if (assertsFirstParameterIsNonnull(m))
                return true;
            else if (m.getName().equals("compareTo") && m.getSignature().endsWith(";)Z") && !m.isStatic())
                return true;
        }
        if (!anyAnnotations(NullnessAnnotation.NONNULL))
            return false;
        XMethodParameter xmp = new XMethodParameter(m, param);
        NullnessAnnotation resolvedAnnotation = getResolvedAnnotation(xmp, true);
        if (false) {
            System.out.println("QQQ parameter " + param + " of " + m + " is " + resolvedAnnotation);
            System.out.println(m.getSignature());
        }

        return resolvedAnnotation == NullnessAnnotation.NONNULL;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * edu.umd.cs.findbugs.ba.INullnessAnnotationDatabase#getResolvedAnnotation
     * (java.lang.Object, boolean)
     */
    @CheckForNull
    @Override
    public NullnessAnnotation getResolvedAnnotation(final Object o, boolean getMinimal) {

        Profiler profiler = Global.getAnalysisCache().getProfiler();
        profiler.start(this.getClass());
        try {
            if (o instanceof XMethodParameter) {
                XMethodParameter mp = (XMethodParameter) o;
                XMethod m = mp.getMethod();
                // TODO: Handle argument to equals specially: generate special
                // bug code for it
                int parameterNumber = mp.getParameterNumber();
                if (parameterNumber == 0) {
                    if (m.getName().equals("equals") && m.getSignature().equals("(Ljava/lang/Object;)Z") && !m.isStatic())
                        return NullnessAnnotation.CHECK_FOR_NULL;
                    else if (m.getName().equals("main") && m.getSignature().equals("([Ljava/lang/String;)V") && m.isStatic()
                            && m.isPublic())
                        return NullnessAnnotation.NONNULL;
                    else if (assertsFirstParameterIsNonnull(m))
                        return NullnessAnnotation.NONNULL;
                    else if (m.getName().equals("compareTo") && m.getSignature().endsWith(";)Z") && !m.isStatic())
                        return NullnessAnnotation.NONNULL;
                }
            } else if (o instanceof XMethod) {
                XMethod m = (XMethod) o;
                String name = m.getName();
                String signature = m.getSignature();
                if (!m.isStatic()
                        && (name.equals("clone") && signature.equals("()Ljava/lang/Object;") || name.equals("toString")
                                && signature.equals("()Ljava/lang/String;") || m.isPrivate() && name.equals("readResolve")
                                && signature.equals("()Ljava/lang/Object;"))) {
                    NullnessAnnotation result = super.getDirectAnnotation(m);
                    if (result != null)
                        return result;
                    return NullnessAnnotation.NONNULL;
                }

            } else if (o instanceof XField) {
                XField f = (XField) o;
                if (f.getName().startsWith("this$"))
                    return NullnessAnnotation.NONNULL;
            }
            NullnessAnnotation result = super.getResolvedAnnotation(o, getMinimal);
            return result;
        } finally {
            profiler.end(this.getClass());
        }
    }

    /**
     * @param m
     * @return
     */
    public static boolean assertsFirstParameterIsNonnull(XMethod m) {
        return (m.getName().equalsIgnoreCase("checkNonNull") || m.getName().equalsIgnoreCase("checkNotNull") || m.getName()
                .equalsIgnoreCase("assertNotNull")) && m.getSignature().startsWith("(Ljava/lang/Object;");
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * edu.umd.cs.findbugs.ba.AnnotationDatabase#addDefaultMethodAnnotation(
     * java.lang.String, edu.umd.cs.findbugs.ba.AnnotationEnumeration)
     */
    @Override
    public void addDefaultMethodAnnotation(String name, NullnessAnnotation annotation) {
        super.addDefaultMethodAnnotation(name, annotation);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * edu.umd.cs.findbugs.ba.AnnotationDatabase#addDefaultAnnotation(java.lang
     * .String, java.lang.String, edu.umd.cs.findbugs.ba.AnnotationEnumeration)
     */
    @Override
    public void addDefaultAnnotation(AnnotationDatabase.Target target, String c, NullnessAnnotation n) {
        super.addDefaultAnnotation(target, c, n);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * edu.umd.cs.findbugs.ba.AnnotationDatabase#addFieldAnnotation(java.lang
     * .String, java.lang.String, java.lang.String, boolean,
     * edu.umd.cs.findbugs.ba.AnnotationEnumeration)
     */
    @Override
    public void addFieldAnnotation(String name, String name2, String sig, boolean isStatic, NullnessAnnotation annotation) {
        super.addFieldAnnotation(name, name2, sig, isStatic, annotation);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * edu.umd.cs.findbugs.ba.AnnotationDatabase#addMethodAnnotation(java.lang
     * .String, java.lang.String, java.lang.String, boolean,
     * edu.umd.cs.findbugs.ba.AnnotationEnumeration)
     */
    @Override
    public void addMethodAnnotation(String name, String name2, String sig, boolean isStatic, NullnessAnnotation annotation) {
        super.addMethodAnnotation(name, name2, sig, isStatic, annotation);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * edu.umd.cs.findbugs.ba.AnnotationDatabase#addMethodParameterAnnotation
     * (java.lang.String, java.lang.String, java.lang.String, boolean, int,
     * edu.umd.cs.findbugs.ba.AnnotationEnumeration)
     */
    @Override
    public void addMethodParameterAnnotation(String name, String name2, String sig, boolean isStatic, int param,
            NullnessAnnotation annotation) {
        super.addMethodParameterAnnotation(name, name2, sig, isStatic, param, annotation);
    }
}
