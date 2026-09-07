// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.refactoring.inline;

import com.intellij.codeInsight.BlockUtils;
import com.intellij.codeInsight.ChangeContextUtil;
import com.intellij.codeInsight.editorActions.DeclarationJoinLinesHandler;
import com.intellij.java.refactoring.JavaRefactoringBundle;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.inline.InlineObjectProcessorUtil.InlineObjectContext;
import com.intellij.refactoring.util.InlineUtil;
import com.intellij.refactoring.util.RefactoringUIUtil;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewDescriptor;
import com.intellij.util.ArrayUtil;
import com.intellij.util.CommonJavaRefactoringUtil;
import com.intellij.util.containers.MultiMap;
import com.siyeh.ig.psiutils.CommentTracker;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.SideEffectChecker;
import com.siyeh.ig.psiutils.StatementExtractor;
import com.siyeh.ig.psiutils.VariableAccessUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.intellij.util.ObjectUtils.tryCast;

/**
 * Performs inlining of object construction together with a subsequent call.
 * E.g. {@code new Point(12, 34).getX()} could be inlined to {@code 12}.
 */
public final class InlineObjectProcessor extends BaseRefactoringProcessor {
  private final InlineObjectContext myContext;

  private InlineObjectProcessor(@NotNull InlineObjectContext context) {
    super(context.project());
    myContext = context;
  }

  @Override
  protected @NotNull UsageViewDescriptor createUsageViewDescriptor(UsageInfo @NotNull [] usages) {
    return new InlineViewDescriptor(myContext.method());
  }

  @Override
  protected @NotNull Collection<? extends PsiElement> getElementsToWrite(@NotNull UsageViewDescriptor descriptor) {
    return Collections.singletonList(myContext.reference().getElement());
  }

  @Override
  protected UsageInfo @NotNull [] findUsages() {
    return new UsageInfo[]{new UsageInfo(myContext.reference())};
  }

  @Override
  protected void performRefactoring(UsageInfo @NotNull [] usages) {
    PsiMethod method = myContext.method();
    PsiReference reference = myContext.reference();
    PsiMethod nextMethod = myContext.nextMethod();
    PsiMethodCallExpression nextCall = myContext.nextCall();
    ChangeContextUtil.encodeContextInfo(method, false);
    PsiMethod ctorCopy = (PsiMethod)method.copy();
    ChangeContextUtil.clearContextInfo(method);
    ChangeContextUtil.encodeContextInfo(nextMethod, false);
    PsiMethod nextCopy = (PsiMethod)nextMethod.copy();
    ChangeContextUtil.clearContextInfo(nextMethod);
    InlineMethodHelper ctorHelper = new InlineMethodHelper(myProject, method, ctorCopy, myContext.newExpression());
    InlineMethodHelper nextHelper = new InlineMethodHelper(myProject, nextMethod, nextCopy, nextCall);
    PsiClass aClass = method.getContainingClass();
    assert aClass != null;
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(myProject);
    PsiCodeBlock target = factory.createCodeBlock();
    List<PsiLocalVariable> fieldLocals = new ArrayList<>();
    for (PsiField field : aClass.getFields()) {
      if (!field.hasModifierProperty(PsiModifier.STATIC)) {
        PsiDeclarationStatement declaration =
          factory.createVariableDeclarationStatement(field.getName(), field.getType(), field.getInitializer(), aClass);
        fieldLocals.add((PsiLocalVariable)((PsiDeclarationStatement)target.add(declaration)).getDeclaredElements()[0]);
      }
    }
    PsiLocalVariable[] ctorParameters = ctorHelper.declareParameters();
    ctorHelper.substituteTypes(ctorParameters);
    InlineTransformer ctorTransformer = InlineTransformer.getSuitableTransformer(method).apply(reference);
    ctorTransformer.transformBody(ctorCopy, reference, PsiTypes.voidType());
    PsiCodeBlock ctorBody = Objects.requireNonNull(ctorCopy.getBody());
    InlineUtil.solveLocalNameConflicts(ctorBody, target, ctorBody);
    updateFieldRefs(ctorCopy, aClass);
    ctorParameters = addRange(target, ctorBody, ctorParameters);

    PsiLocalVariable[] nextParameters = nextHelper.declareParameters();
    nextHelper.substituteTypes(nextParameters);
    InlineTransformer nextTransformer = InlineTransformer.getSuitableTransformer(nextMethod).apply(nextCall.getMethodExpression());
    PsiLocalVariable result = nextTransformer.transformBody(nextCopy, nextCall.getMethodExpression(), nextCall.getType());
    PsiCodeBlock nextBody = Objects.requireNonNull(nextCopy.getBody());
    InlineUtil.solveLocalNameConflicts(nextBody, target, nextBody);
    updateFieldRefs(nextCopy, aClass);
    if (result != null) {
      PsiLocalVariable[] resultAndParameters = ArrayUtil.prepend(result, nextParameters);
      resultAndParameters = addRange(target, nextBody, resultAndParameters);
      result = resultAndParameters[0];
      nextParameters = Arrays.copyOfRange(resultAndParameters, 1, resultAndParameters.length);
    }
    else {
      nextParameters = addRange(target, nextBody, nextParameters);
    }

    InlineUtil.solveLocalNameConflicts(target, reference.getElement(), target);
    ctorHelper.initializeParameters(ctorParameters);
    nextHelper.initializeParameters(nextParameters);

    removeRedundantFieldVars(fieldLocals, target);
    ctorHelper.inlineParameters(ctorParameters);
    nextHelper.inlineParameters(nextParameters);

    PsiElement anchor = CommonJavaRefactoringUtil.getParentStatement(nextCall, true);
    assert anchor != null;
    PsiElement anchorParent = anchor.getParent();
    PsiStatement[] statements = target.getStatements();
    PsiElement firstBodyElement = target.getFirstBodyElement();
    if (firstBodyElement instanceof PsiWhiteSpace) firstBodyElement = PsiTreeUtil.skipWhitespacesForward(firstBodyElement);
    PsiElement firstAdded = null;
    if (firstBodyElement != null && firstBodyElement != target.getRBrace()) {
      int last = statements.length - 1;

      final PsiElement rBraceOrReturnStatement =
        last >= 0 ? PsiTreeUtil.skipWhitespacesAndCommentsForward(statements[last]) : target.getLastBodyElement();
      assert rBraceOrReturnStatement != null;
      final PsiElement beforeRBraceStatement = rBraceOrReturnStatement.getPrevSibling();
      assert beforeRBraceStatement != null;

      firstAdded = anchorParent.addRangeBefore(firstBodyElement, beforeRBraceStatement, anchor);
      ChangeContextUtil.decodeContextInfo(anchorParent, null, null);
    }

    PsiReferenceExpression resultUsage = InlineMethodProcessorUtil.replaceCall(factory, nextCall, firstAdded, result);
    if (resultUsage != null) {
      PsiLocalVariable resultVar = ExpressionUtils.resolveLocalVariable(resultUsage);
      if (resultVar != null) {
        InlineUtil.tryInlineResultVariable(resultVar, resultUsage);
      }
    }
  }

  private static void removeRedundantFieldVars(List<PsiLocalVariable> vars, PsiCodeBlock block) {
    for (PsiLocalVariable var : vars) {
      List<PsiReferenceExpression> references = VariableAccessUtils.getVariableReferences(var, block);
      PsiAssignmentExpression firstAssignment = null;
      List<PsiAssignmentExpression> assignments = new ArrayList<>();
      for (PsiReferenceExpression reference : references) {
        PsiAssignmentExpression assignment = tryCast(PsiUtil.skipParenthesizedExprUp(reference.getParent()), PsiAssignmentExpression.class);
        if (assignment != null && assignment.getOperationTokenType().equals(JavaTokenType.EQ) &&
            PsiUtil.skipParenthesizedExprDown(assignment.getLExpression()) == reference &&
            assignment.getParent() instanceof PsiExpressionStatement) {
          assignments.add(assignment);
          if (firstAssignment == null && assignment.getParent().getParent() == block) {
            firstAssignment = assignment;
          }
        }
        else {
          assignments = null;
          break;
        }
      }
      if (assignments != null) {
        for (PsiAssignmentExpression assignment : assignments) {
          PsiExpressionStatement statement = (PsiExpressionStatement)assignment.getParent();
          PsiExpression expression = assignment.getRExpression();
          CommentTracker ct = new CommentTracker();
          if (expression != null) {
            List<PsiExpression> sideEffects = SideEffectChecker.extractSideEffectExpressions(expression);
            sideEffects.forEach(ct::markUnchanged);
            PsiStatement[] statements = StatementExtractor.generateStatements(sideEffects, expression);
            if (statements.length > 0) {
              BlockUtils.addBefore(statement, statements);
            }
          }
          ct.deleteAndRestoreComments(statement);
        }
        new CommentTracker().deleteAndRestoreComments(var);
      }
      else if (firstAssignment != null) {
        var = DeclarationJoinLinesHandler.joinDeclarationAndAssignment(var, firstAssignment);
        InlineUtil.tryInlineGeneratedLocal(var, false);
      }
    }
  }

  private static PsiLocalVariable[] addRange(PsiCodeBlock target, PsiCodeBlock body, PsiLocalVariable[] declaredVars) {
    PsiElement firstBodyElement = body.getFirstBodyElement();
    PsiElement lastBodyElement = body.getLastBodyElement();
    if (firstBodyElement == null || lastBodyElement == null) return declaredVars;
    PsiElement firstAdded = target.addRange(firstBodyElement, lastBodyElement);
    PsiLocalVariable[] updatedVars = new PsiLocalVariable[declaredVars.length];
    int index = 0;
    for (PsiElement e = firstAdded; index < updatedVars.length && e != null; e = e.getNextSibling()) {
      if (e instanceof PsiDeclarationStatement) {
        PsiElement[] elements = ((PsiDeclarationStatement)e).getDeclaredElements();
        if (elements.length == 1) {
          PsiLocalVariable var = tryCast(elements[0], PsiLocalVariable.class);
          if (var != null) {
            if (var.getName().equals(declaredVars[index].getName())) {
              updatedVars[index++] = var;
            }
          }
        }
      }
    }
    assert index == updatedVars.length;
    return updatedVars;
  }

  private static void updateFieldRefs(PsiMethod method, PsiClass aClass) {
    PsiCodeBlock body = method.getBody();
    assert body != null;
    for (PsiThisExpression thisExpression : PsiTreeUtil.findChildrenOfType(body, PsiThisExpression.class)) {
      PsiElement parent = PsiUtil.skipParenthesizedExprUp(thisExpression.getParent());
      if (parent instanceof PsiReferenceExpression) {
        PsiField field = tryCast(((PsiReferenceExpression)parent).resolve(), PsiField.class);
        if (field != null && field.getContainingClass() == aClass) {
          thisExpression.delete();
        }
      }
    }
  }

  @Override
  protected boolean preprocessUsages(@NotNull Ref<UsageInfo[]> refUsages) {
    PsiMethod method = myContext.method();
    final UsageInfo[] usagesIn = refUsages.get();
    final MultiMap<PsiElement, String> conflicts = new MultiMap<>();
    final ReferencedElementsCollector collector = new ReferencedElementsCollector();
    method.accept(collector);
    myContext.nextMethod().accept(collector);

    final Map<PsiMember, Set<PsiMember>> containersToReferenced = InlineMethodProcessorUtil.getInaccessible(collector.myReferencedMembers, usagesIn, method);

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
    return showConflicts(conflicts, usagesIn);
  }

  @Override
  protected @NotNull String getCommandName() {
    return JavaRefactoringBundle.message("inline.object.command.name");
  }

  public static @Nullable InlineObjectProcessor create(PsiReference reference, PsiMethod method) {
    InlineObjectContext context = InlineObjectProcessorUtil.createContext(reference, method);
    if (context == null) return null;
    return new InlineObjectProcessor(context);
  }
}
