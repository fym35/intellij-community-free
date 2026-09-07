// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.refactoring.inline;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiQualifiedExpression;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.util.PsiUtil;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.util.RefactoringUIUtil;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.CommonJavaRefactoringUtil;
import com.intellij.util.containers.MultiMap;
import com.siyeh.ig.psiutils.ControlFlowUtils;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.TypeUtils;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.intellij.openapi.util.NlsContexts.DialogMessage;
import static com.intellij.util.ObjectUtils.tryCast;

/**
 * Util class that provides steps for "Inline Object" refactoring that can be accessed outside of
 * {@link com.intellij.refactoring.BaseRefactoringProcessor}
 */
@ApiStatus.Internal
public final class InlineObjectProcessorUtil {
  private InlineObjectProcessorUtil() { }

  /**
   * Computes the context to perform the "Inline Object" refactoring.
   *
   * @param reference - The reference to the constructor call on which the refactoring was invoked.
   * @param method - The constructor to be inlined.
   * @return The context, or null when the object cannot be inlined.
   */
  @Contract("null, _ -> null")
  public static @Nullable InlineObjectContext createContext(PsiReference reference, PsiMethod method) {
    if (!canInlineConstructorAndChainCall(reference, method)) {
      return null;
    }
    PsiElement element = reference.getElement();
    PsiNewExpression newExpression = tryCast(element.getParent(), PsiNewExpression.class);
    assert newExpression != null;
    PsiMethodCallExpression nextCall = ExpressionUtils.getCallForQualifier(newExpression);
    assert nextCall != null;
    PsiMethod nextMethod = nextCall.resolveMethod();
    assert nextMethod != null;
    PsiElement nav = nextMethod.getNavigationElement();
    if (nav instanceof PsiMethod) {
      nextMethod = (PsiMethod)nav;
    }
    return new InlineObjectContext(method, reference, newExpression, nextCall, nextMethod);
  }

  @Contract("null, _ -> false")
  private static boolean canInlineConstructorAndChainCall(PsiReference reference, PsiMethod method) {
    if (reference == null) return false;
    PsiElement element = reference.getElement();
    if (!(element instanceof PsiJavaCodeReferenceElement)) return false;
    PsiNewExpression expression = tryCast(element.getParent(), PsiNewExpression.class);
    if (expression == null) return false;
    PsiMethodCallExpression call = ExpressionUtils.getCallForQualifier(expression);
    if (call == null) return false;
    if (CommonJavaRefactoringUtil.getParentStatement(call, true) == null) return false;
    PsiMethod nextMethod = call.resolveMethod();
    if (nextMethod == null) return false;
    PsiElement nav = nextMethod.getNavigationElement();
    if (nav instanceof PsiMethod) {
      nextMethod = (PsiMethod)nav;
    }
    PsiClass aClass = method.getContainingClass();
    if (aClass == null) return false;
    if (aClass.getContainingClass() != null && !aClass.hasModifierProperty(PsiModifier.STATIC)) return false;

    PsiClassType[] supers = aClass.getExtendsListTypes();
    if (supers.length > 1) return false;
    if (supers.length == 1 && !isStatelessSuperClass(supers[0], new HashSet<>())) return false;
    for (PsiField field : aClass.getFields()) {
      if (!field.hasModifierProperty(PsiModifier.STATIC)) {
        PsiExpression initializer = field.getInitializer();
        if (initializer != null && mayLeakThis(initializer)) return false;
      }
    }
    for (PsiClassInitializer initializer : aClass.getInitializers()) {
      if (!initializer.hasModifierProperty(PsiModifier.STATIC)) {
        return false;
      }
    }
    return !mayLeakThis(method) && !mayLeakThis(nextMethod);
  }

  private static boolean isStatelessSuperClass(PsiClassType psiType, Set<PsiClass> checked) {
    if (TypeUtils.isJavaLangObject(psiType) || TypeUtils.typeEquals(CommonClassNames.JAVA_LANG_RECORD, psiType)) return true;
    PsiClass psiClass = psiType.resolve();
    if (psiClass == null || !checked.add(psiClass)) return false;
    PsiMethod[] constructors = psiClass.getConstructors();
    for (PsiMethod constructor : constructors) {
      if (constructor.getParameterList().isEmpty()) {
        PsiElement nav = constructor.getNavigationElement();
        if (nav instanceof PsiMethod) {
          constructor = (PsiMethod)nav;
        }
        PsiCodeBlock body = constructor.getBody();
        if (body == null || !ControlFlowUtils.isEmptyCodeBlock(body)) return false;
      }
    }
    for (PsiField field : psiClass.getFields()) {
      if (!field.hasModifierProperty(PsiModifier.STATIC)) return false;
    }
    PsiClassType[] supers = psiClass.getExtendsListTypes();
    return supers.length == 0 || supers.length == 1 && isStatelessSuperClass(supers[0], checked);
  }

  private static boolean mayLeakThis(PsiMethod method) {
    if (method == null) return true;
    PsiCodeBlock body = method.getBody();
    if (body == null) return true;
    return mayLeakThis(body);
  }

  private static boolean mayLeakThis(PsiElement body) {
    class Visitor extends JavaRecursiveElementWalkingVisitor {
      boolean leak = false;

      @Override
      public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
        super.visitMethodCallExpression(call);
        PsiExpression qualifier = ExpressionUtils.getEffectiveQualifier(call.getMethodExpression());
        if (qualifier instanceof PsiQualifiedExpression) {
          leak = true;
          stopWalking();
        }
      }

      @Override
      public void visitNewExpression(@NotNull PsiNewExpression expression) {
        super.visitNewExpression(expression);
        if (expression.getQualifier() == null) {
          PsiJavaCodeReferenceElement reference = expression.getClassReference();
          if (reference != null) {
            PsiClass target = tryCast(reference.resolve(), PsiClass.class);
            if (target != null && target.getContainingClass() != null && !target.hasModifierProperty(PsiModifier.STATIC)) {
              leak = true;
              stopWalking();
            }
          }
        }
      }

      @Override
      public void visitThisExpression(@NotNull PsiThisExpression expression) {
        super.visitThisExpression(expression);
        PsiElement parent = PsiUtil.skipParenthesizedExprUp(expression.getParent());
        if (!(parent instanceof PsiReferenceExpression)) {
          leak = true;
          stopWalking();
        }
      }
    }
    Visitor visitor = new Visitor();
    body.accept(visitor);
    return visitor.leak;
  }

  public static UsageInfo @NotNull [] findUsages(@NotNull InlineObjectContext context) {
    return new UsageInfo[]{new UsageInfo(context.reference())};
  }

  public static void collectConflicts(@NotNull InlineObjectContext context,
                                      UsageInfo @NotNull [] usages,
                                      @NotNull MultiMap<PsiElement, @DialogMessage String> conflicts) {
    PsiMethod method = context.method();
    final ReferencedElementsCollector collector = new ReferencedElementsCollector();
    method.accept(collector);
    context.nextMethod().accept(collector);

    final Map<PsiMember, Set<PsiMember>> containersToReferenced =
      InlineMethodProcessorUtil.getInaccessible(collector.myReferencedMembers, usages, method);

    containersToReferenced.forEach((container, referencedInaccessible) -> {
      for (PsiMember referenced : referencedInaccessible) {
        if (referenced instanceof PsiField && !referenced.hasModifierProperty(PsiModifier.STATIC) &&
            referenced.getContainingClass() == method.getContainingClass()) {
          // Instance fields will be inlined
          continue;
        }
        final String referencedDescription = RefactoringUIUtil.getDescription(referenced, true);
        final String containerDescription = RefactoringUIUtil.getDescription(container, true);
        String message = RefactoringBundle.message("0.that.is.used.in.inlined.method.is.not.accessible.from.call.site.s.in.1",
                                                   referencedDescription, containerDescription);
        conflicts.putValue(container, StringUtil.capitalize(message));
      }
    });
  }

  /**
   * Holds the values that stay the same during one "Inline Object" refactoring.
   *
   * @param method - The constructor to be inlined.
   * @param reference - The reference to the {@link method} to be inlined.
   * @param newExpression - The object creation expression that holds the {@link reference}.
   * @param nextCall - The call that is chained to the {@link newExpression}, for example {@code getX()} in {@code new Point().getX()}.
   * @param nextMethod - The method that the {@link nextCall} resolves to.
   */
  @ApiStatus.Internal
  public record InlineObjectContext(@NotNull PsiMethod method,
                                    @NotNull PsiReference reference,
                                    @NotNull PsiNewExpression newExpression,
                                    @NotNull PsiMethodCallExpression nextCall,
                                    @NotNull PsiMethod nextMethod) {
    public @NotNull PsiElementFactory factory() {
      return JavaPsiFacade.getElementFactory(project());
    }

    public @NotNull Project project() {
      return method.getProject();
    }
  }
}