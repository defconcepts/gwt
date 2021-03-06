/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.jjs.ast;

import com.google.gwt.dev.common.InliningMode;
import com.google.gwt.dev.javac.JsInteropUtil;
import com.google.gwt.dev.jjs.InternalCompilerException;
import com.google.gwt.dev.jjs.SourceInfo;
import com.google.gwt.dev.jjs.SourceOrigin;
import com.google.gwt.dev.jjs.ast.js.JsniMethodBody;
import com.google.gwt.dev.jjs.impl.JjsUtils;
import com.google.gwt.dev.util.StringInterner;
import com.google.gwt.dev.util.collect.Lists;
import com.google.gwt.thirdparty.guava.common.collect.Sets;

import java.beans.Introspector;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * A Java method implementation.
 */
public class JMethod extends JNode implements JMember, CanBeAbstract, CanHaveSuppressedWarnings {

  /**
   * Indicates whether a method is a JsProperty accessor.
   */
  public enum JsPropertyAccessorType {
    /**
     * Not a property accessor.
     */
    NONE,
    /**
     * A getter property accessor. Usually in the form of getX()/isX().
     */
    GETTER("get") {
      @Override
      public String computeName(JMethod member) {
        String methodName = member.getName();
        if (startsWithCamelCase(methodName, "get")) {
          return Introspector.decapitalize(methodName.substring(3));
        }
        if (startsWithCamelCase(methodName, "is")) {
          return Introspector.decapitalize(methodName.substring(2));
        }
        return JsInteropUtil.INVALID_JSNAME;
      }
    },
    /**
     * A setter JsProperty accessor. Usually in the form of setX(x).
     */
    SETTER("set") {
      @Override
      public String computeName(JMethod member) {
        String methodName = member.getName();
        if (startsWithCamelCase(methodName, "set")) {
          return Introspector.decapitalize(methodName.substring(3));
        }
        return JsInteropUtil.INVALID_JSNAME;
      }
    },
    /**
     * A property accessor but doesn't match setter/getter patterns.
     */
    UNDEFINED;

    private String key;

    JsPropertyAccessorType() {
    }

    JsPropertyAccessorType(String key) {
      this.key = key;
    }

    public String getKey() {
      return key;
    }

    public boolean isPropertyAccessor() {
      return key != null;
    }

    public String computeName(JMethod method) {
      return method.getName();
    }

    private static boolean startsWithCamelCase(String string, String prefix) {
      return string.length() > prefix.length() && string.startsWith(prefix)
          && Character.isUpperCase(string.charAt(prefix.length()));
    }
  }

  public static final Comparator<JMethod> BY_SIGNATURE_COMPARATOR = new Comparator<JMethod>() {
    @Override
    public int compare(JMethod m1, JMethod m2) {
      return m1.getSignature().compareTo(m2.getSignature());
    }
  };

  private String jsName;
  private boolean exported;
  private String jsNamespace;
  private JsPropertyAccessorType jsPropertyType = JsPropertyAccessorType.NONE;
  private Specialization specialization;
  private InliningMode inliningMode = InliningMode.NORMAL;
  private boolean preventDevirtualization = false;
  private boolean hasSideEffects = true;
  private boolean defaultMethod = false;
  private boolean syntheticAccidentalOverride = false;
  private Set<String> suppressedWarnings;
  private boolean isJsOverlay = false;

  @Override
  public void setJsMemberInfo(String namespace, String name, boolean exported) {
    this.jsName = name;
    this.jsNamespace = namespace;
    this.exported = exported;
  }

  public boolean isJsInteropEntryPoint() {
    return exported && !needsDynamicDispatch() && !isJsNative();
  }

  public boolean canBeCalledExternally() {
    if (exported || isJsFunctionMethod()) {
      return true;
    }
    for (JMethod overriddenMethod : getOverriddenMethods()) {
      if (overriddenMethod.exported || overriddenMethod.isJsFunctionMethod()) {
        return true;
      }
    }
    return false;
  }

  public boolean canBeImplementedExternally() {
    return isJsNative() || isJsFunctionMethod() || isJsInterfaceMethod();
  }

  private boolean isJsInterfaceMethod() {
    return isInterfaceMethod() && enclosingType.isJsType();
  }

  private boolean isInterfaceMethod() {
    return enclosingType instanceof JInterfaceType;
  }

  @Override
  public String getJsNamespace() {
    if (jsNamespace == null) {
      jsNamespace = enclosingType.getQualifiedJsName();
    }
    return jsNamespace;
  }

  @Override
  public String getQualifiedJsName() {
    String namespace = getJsNamespace();
    if (jsName.isEmpty()) {
      assert !needsDynamicDispatch();
      return namespace;
    } else if (namespace.isEmpty()) {
      assert !needsDynamicDispatch();
      return jsName;
    } else {
      return namespace + (isStatic() ? "." : ".prototype.") + jsName;
    }
  }

  @Override
  public String getJsName() {
    String jsMemberName = jsName;
    for (JMethod override : getOverriddenMethods()) {
      String jsMemberOverrideName = override.jsName;
      if (jsMemberOverrideName == null) {
        continue;
      }
      if (jsMemberName != null && !jsMemberName.equals(jsMemberOverrideName)) {
        return JsInteropUtil.INVALID_JSNAME;
      }
      jsMemberName = jsMemberOverrideName;
    }
    return jsMemberName;
  }

  public boolean isJsConstructor() {
    return isConstructor() && jsName != null;
  }

  /**
   * Returns {@code true} if this method is the first JsMethod in the method hierarchy that exposes
   * an existing non-JsMethod inside a class.
   */
  public boolean exposesNonJsMethod() {
    if (isInterfaceMethod() || !isOrOverridesJsMethod()) {
      return false;
    }

    boolean hasNonJsMethodParent = false;
    for (JMethod overriddenMethod : overriddenMethods) {
      if (!overriddenMethod.isOrOverridesJsMethod()) {
        hasNonJsMethodParent = true;
      }
      if (overriddenMethod.exposesNonJsMethod()) {
        return false; // some other method already exposed this method.
      }
    }

    return hasNonJsMethodParent;
  }

  public boolean isOrOverridesJsMethod() {
    if (jsName != null) {
      return true;
    }
    for (JMethod overriddenMethod : getOverriddenMethods()) {
      if (overriddenMethod.jsName != null) {
        return true;
      }
    }
    return false;
  }

  public void setJsPropertyInfo(String jsName, JsPropertyAccessorType jsPropertyType) {
    this.jsName = jsName != null ? jsName : jsPropertyType.computeName(this);
    this.jsPropertyType = jsPropertyType;
  }

  public JsPropertyAccessorType getJsPropertyAccessorType() {
    if (isJsPropertyAccessor()) {
      return jsPropertyType;
    }
    for (JMethod overriddenMethod : getOverriddenMethods()) {
      if (overriddenMethod.isJsPropertyAccessor()) {
        return overriddenMethod.jsPropertyType;
      }
    }
    return JsPropertyAccessorType.NONE;
  }

  private boolean isJsPropertyAccessor() {
    return jsPropertyType != JsPropertyAccessorType.NONE;
  }

  private boolean isJsFunctionMethod() {
    return enclosingType != null && enclosingType.isJsFunction();
  }

  public boolean isOrOverridesJsFunctionMethod() {
    if (isJsFunctionMethod()) {
      return true;
    }
    for (JMethod overriddenMethod : getOverriddenMethods()) {
      if (overriddenMethod.isJsFunctionMethod()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isJsNative() {
    return (isAbstract || body == null) && jsName != null;
  }

  public boolean isJsOverlay() {
    return isJsOverlay || (getEnclosingType() != null && getEnclosingType().isJsoType());
  }

  public void setSyntheticAccidentalOverride() {
    this.syntheticAccidentalOverride = true;
  }

  public boolean isSyntheticAccidentalOverride() {
    return syntheticAccidentalOverride;
  }

  public void setSpecialization(List<JType> paramTypes, JType returnsType, String targetMethod) {
    this.specialization = new Specialization(paramTypes,
        returnsType == null ? this.getOriginalReturnType() : returnsType, targetMethod);
  }

  public Specialization getSpecialization() {
    return specialization;
  }

  public void removeSpecialization() {
    specialization = null;
  }

  public boolean isInliningAllowed() {
    return inliningMode != InliningMode.DO_NOT_INLINE;
  }

  public InliningMode getInliningMode() {
    return inliningMode;
  }

  public void setInliningMode(InliningMode inliningMode) {
    this.inliningMode = inliningMode;
  }

  public boolean hasSideEffects() {
    return hasSideEffects;
  }

  public void setHasSideEffects(boolean hasSideEffects) {
    this.hasSideEffects = hasSideEffects;
  }

  public void setDefaultMethod() {
    this.defaultMethod = true;
  }

  public void setJsOverlay() {
    this.isJsOverlay = true;
  }

  public boolean isDefaultMethod() {
    return defaultMethod;
  }

  public boolean isDevirtualizationAllowed() {
    return !preventDevirtualization;
  }

  public void disallowDevirtualization() {
    this.preventDevirtualization = true;
  }

  /**
   * AST representation of @SpecializeMethod.
   */
  public static class Specialization implements Serializable {
    private List<JType> params;
    private JType returns;
    private String target;
    private JMethod targetMethod;

    public Specialization(List<JType> params, JType returns, String target) {
      this.params = params;
      this.returns = returns;
      this.target = target;
    }

    public List<JType> getParams() {
      return params;
    }

    public JType getReturns() {
      return returns;
    }

    public String getTarget() {
      return target;
    }

    public JMethod getTargetMethod() {
      return targetMethod;
    }

    public void resolve(List<JType> resolvedParams, JType resolvedReturn, JMethod targetMethod) {
      this.params = resolvedParams;
      this.returns = resolvedReturn;
      this.targetMethod = targetMethod;
    }
  }

  private static class ExternalSerializedForm implements Serializable {

    private final JDeclaredType enclosingType;
    private final String signature;

    public ExternalSerializedForm(JMethod method) {
      enclosingType = method.getEnclosingType();
      signature = method.getSignature();
    }

    private Object readResolve() {
      return new JMethod(signature, enclosingType, false);
    }
  }

  private static class ExternalSerializedNullMethod implements Serializable {
    public static final ExternalSerializedNullMethod INSTANCE = new ExternalSerializedNullMethod();

    private Object readResolve() {
      return NULL_METHOD;
    }
  }

  public static final JMethod NULL_METHOD = new JMethod(SourceOrigin.UNKNOWN, "nullMethod", null,
      JReferenceType.NULL_TYPE, false, false, true, AccessModifier.PUBLIC);

  static {
    NULL_METHOD.setSynthetic();
    NULL_METHOD.freezeParamTypes();
  }

  protected transient String signature;

  /**
   * The access modifier; stored as an int to reduce memory / serialization footprint.
   */
  private int access;

  /**
   * Special serialization treatment.
   */
  private transient JAbstractMethodBody body = null;
  private final JDeclaredType enclosingType;
  private boolean isAbstract;
  private boolean isFinal;
  private final boolean isStatic;
  private boolean isSynthetic = false;
  private boolean isForwarding = false;
  private final String name;

  private List<JType> originalParamTypes;
  private JType originalReturnType;

  /**
   * References to any methods which this method overrides. This should be an
   * EXHAUSTIVE list, that is, if C overrides B overrides A, then C's overrides
   * list will contain both A and B.
   */
  private Set<JMethod> overriddenMethods = Sets.newLinkedHashSet();
  private Set<JMethod> overridingMethods = Sets.newLinkedHashSet();

  private List<JParameter> params = Collections.emptyList();
  private JType returnType;
  private List<JClassType> thrownExceptions = Collections.emptyList();

  /**
   * These are only supposed to be constructed by JProgram.
   */
  public JMethod(SourceInfo info, String name, JDeclaredType enclosingType, JType returnType,
      boolean isAbstract, boolean isStatic, boolean isFinal, AccessModifier access) {
    super(info);
    this.name = StringInterner.get().intern(name);
    this.enclosingType = enclosingType;
    this.returnType = returnType;
    this.isAbstract = isAbstract;
    this.isStatic = isStatic;
    this.isFinal = isFinal;
    this.access = access.ordinal();
  }

  /**
   * Creates an externalized representation for a method that needs to be resolved.
   * Useful to refer to methods of magic classes during GwtAstBuilder execution.
   *
   * @param fullClassName the class where the method is defined.
   * @param signature the signature of the method (including its name).
   *
   */
  public static JMethod getExternalizedMethod(String fullClassName, String signature,
      boolean isStatic) {

    JClassType cls = new JClassType(fullClassName);
    return new JMethod(signature, cls, isStatic);
  }

  /**
   * Construct a bare-bones deserialized external method.
   */
  private JMethod(String signature, JDeclaredType enclosingType, boolean isStatic) {
    this(SourceOrigin.UNKNOWN, StringInterner.get().intern(
        signature.substring(0, signature.indexOf('('))), enclosingType, null, false, isStatic,
        false, AccessModifier.PUBLIC);
    this.signature = signature;
  }

  /**
   * Add a method that this method overrides.
   */
  public void addOverriddenMethod(JMethod overriddenMethod) {
    assert canBePolymorphic() : this + " is not polymorphic";
    assert overriddenMethod != this : this + " cannot override itself";
    overriddenMethods.add(overriddenMethod);
  }

  /**
   * Add a method that overrides this method.
   */
  public void addOverridingMethod(JMethod overridingMethod) {
    assert canBePolymorphic() : this + " is not polymorphic";
    assert overridingMethod != this : this + " cannot override itself";
    overridingMethods.add(overridingMethod);
  }
  /**
   * Adds a parameter to this method.
   */
  public void addParam(JParameter x) {
    params = Lists.add(params, x);
  }

  public void addThrownException(JClassType exceptionType) {
    thrownExceptions = Lists.add(thrownExceptions, exceptionType);
  }

  public void addThrownExceptions(List<JClassType> exceptionTypes) {
    thrownExceptions = Lists.addAll(thrownExceptions, exceptionTypes);
  }

  /**
   * Returns true if this method can participate in virtual dispatch. Returns
   * true for non-private instance methods; false for static methods, private
   * instance methods, and constructors.
   */
  public boolean canBePolymorphic() {
    return !isStatic() && !isPrivate();
  }

  public void freezeParamTypes() {
    List<JType> paramTypes = new ArrayList<JType>();
    for (JParameter param : params) {
      paramTypes.add(param.getType());
    }
    setOriginalTypes(returnType, paramTypes);
  }

  /**
   * Returns {@code true} if this method is the first method in the method hierarchy that increases
   * the visibility of a package private method.
   */
  public boolean exposesPackagePrivateMethod() {
    if (isPrivate() || isPackagePrivate()) {
      return false;
    }

    boolean hasPackageVisibleParent = false;
    for (JMethod overriddenMethod : overriddenMethods) {
      if (overriddenMethod.isInterfaceMethod()) {
        continue;
      }
      if (!overriddenMethod.isPackagePrivate()) {
        return false; // some other method already exposed this method.
      }
      hasPackageVisibleParent = true;
    }

    return hasPackageVisibleParent;
  }

  public AccessModifier getAccess() {
    return AccessModifier.values()[access];
  }

  public JAbstractMethodBody getBody() {
    assert !isExternal() : "External types do not have method bodies.";
    return body;
  }

  @Override
  public JDeclaredType getEnclosingType() {
    return enclosingType;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getQualifiedName() {
    return enclosingType.getName() + "." + getSignature();
  }

  public List<JType> getOriginalParamTypes() {
    return originalParamTypes;
  }

  public JType getOriginalReturnType() {
    return originalReturnType;
  }

  /**
   * Returns the transitive closure of all the methods this method overrides; this set is ordered
   * from most specific to least specific, where class methods appear before interface methods.
   */
  public Set<JMethod> getOverriddenMethods() {
    return overriddenMethods;
  }

  /**
   * Returns the transitive closure of all the methods that override this method; caveat this
   * list is only complete in monolithic compiles and should not be used in incremental compiles.
   * The returned set is ordered in such a way that most specific overriding methods appear after
   * less specific ones.
   */
  public Set<JMethod> getOverridingMethods() {
    return overridingMethods;
  }

  /**
   * Returns the parameters of this method.
   */
  public List<JParameter> getParams() {
    return params;
  }

  public String getSignature() {
    if (signature == null) {
      signature = StringInterner.get().intern(JjsUtils.computeSignature(
          name, getOriginalParamTypes(), getOriginalReturnType(), isConstructor()));
    }
    return signature;
  }

  public String getJsniSignature(boolean includeEnclosingClass, boolean includeReturnType) {
    StringBuilder sb = new StringBuilder();
    if (includeEnclosingClass) {
      sb.append(getEnclosingType().getName());
      sb.append("::");
    }
    sb.append(name);
    sb.append('(');
    for (JType type : getOriginalParamTypes()) {
      sb.append(type.getJsniSignatureName());
    }
    sb.append(')');
    if (includeReturnType) {
      sb.append(originalReturnType.getJsniSignatureName());
    }
    return sb.toString();
  }

  public List<JClassType> getThrownExceptions() {
    return thrownExceptions;
  }

  @Override
  public JType getType() {
    return returnType;
  }

  @Override
  public boolean isAbstract() {
    return isAbstract;
  }

  public boolean isConstructor() {
    return false;
  }

  public boolean isPackagePrivate() {
    return access == AccessModifier.DEFAULT.ordinal();
  }

  public boolean isExternal() {
    return getEnclosingType() != null && getEnclosingType().isExternal();
  }

  @Override
  public boolean isFinal() {
    return isFinal;
  }

  public boolean isForwarding() {
    return isForwarding;
  }

  public boolean isJsniMethod() {
    if (body == null) {
      return false;
    } else {
      return body.isJsniMethodBody();
    }
  }

  public boolean isPrivate() {
    return access == AccessModifier.PRIVATE.ordinal();
  }

  @Override
  public boolean isPublic() {
    return access == AccessModifier.PUBLIC.ordinal();
  }

  @Override
  public boolean isStatic() {
    return isStatic;
  }

  public boolean isSynthetic() {
    return isSynthetic;
  }

  /**
   * Returns <code>true</code> if this method can participate in instance
   * dispatch.
   */
  @Override
  public boolean needsDynamicDispatch() {
    return !isStatic();
  }

  /**
   * Removes the parameter at the specified index.
   */
  public void removeParam(int index) {
    params = Lists.remove(params, index);
    if (isJsniMethod()) {
      ((JsniMethodBody) getBody()).getFunc().getParameters().remove(index);
    }
  }

  /**
   * Resolve an external references during AST stitching.
   */
  public void resolve(JType originalReturnType, List<JType> originalParamTypes, JType returnType,
      List<JClassType> thrownExceptions) {
    if (getClass().desiredAssertionStatus()) {
      assert originalReturnType.replaces(this.originalReturnType);
      assert JType.replaces(originalParamTypes, this.originalParamTypes);
      assert returnType.replaces(this.returnType);
      assert JType.replaces(thrownExceptions, this.thrownExceptions);
    }
    this.originalReturnType = originalReturnType;
    this.originalParamTypes = Lists.normalize(originalParamTypes);
    this.returnType = returnType;
    this.thrownExceptions = Lists.normalize(thrownExceptions);
  }

  public void setAbstract(boolean isAbstract) {
    this.isAbstract = isAbstract;
  }

  public void setBody(JAbstractMethodBody body) {
    this.body = body;
    if (body != null) {
      body.setMethod(this);
    }
  }

  @Override
  public void setFinal() {
    setFinal(true);
  }

  public void setFinal(boolean isFinal) {
    this.isFinal = isFinal;
  }

  public void setForwarding() {
    isForwarding = true;
  }

  public void setOriginalTypes(JType returnType, List<JType> paramTypes) {
    if (originalParamTypes != null) {
      throw new InternalCompilerException("Param types already frozen");
    }
    originalReturnType = returnType;
    originalParamTypes = Lists.normalize(paramTypes);
  }

  public void setSynthetic() {
    isSynthetic = true;
  }

  public void setType(JType newType) {
    returnType = newType;
  }

  @Override
  public Set<String> getSuppressedWarnings() {
    return suppressedWarnings;
  }

  @Override
  public void setSuppressedWarnings(Set<String> suppressedWarnings) {
    this.suppressedWarnings = suppressedWarnings;
  }

  @Override
  public void traverse(JVisitor visitor, Context ctx) {
    if (visitor.visit(this, ctx)) {
      visitChildren(visitor);
    }
    visitor.endVisit(this, ctx);
  }

  protected void visitChildren(JVisitor visitor) {
    params = visitor.acceptImmutable(params);
    if (body != null) {
      body = (JAbstractMethodBody) visitor.accept(body);
    }
  }

  protected Object writeReplace() {
    if (isExternal()) {
      return new ExternalSerializedForm(this);
    } else if (this == NULL_METHOD) {
      return ExternalSerializedNullMethod.INSTANCE;
    } else {
      return this;
    }
  }

  /**
   * See {@link #writeBody(ObjectOutputStream)}.
   *
   * @see #writeBody(ObjectOutputStream)
   */
  void readBody(ObjectInputStream stream) throws IOException, ClassNotFoundException {
    body = (JAbstractMethodBody) stream.readObject();
  }

  boolean replaces(JMethod originalMethod) {
    if (this == originalMethod) {
      return true;
    }
    return originalMethod.isExternal() && originalMethod.getSignature().equals(this.getSignature())
        && this.getEnclosingType().replaces(originalMethod.getEnclosingType());
  }

  /**
   * After all types, fields, and methods are written to the stream, this method
   * writes method bodies to the stream.
   *
   * @see JProgram#writeObject(ObjectOutputStream)
   */
  void writeBody(ObjectOutputStream stream) throws IOException {
    stream.writeObject(body);
  }
}
