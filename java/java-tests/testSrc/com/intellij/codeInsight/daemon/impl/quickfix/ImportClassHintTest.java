// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiReference;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutionException;

public class ImportClassHintTest extends LightJavaCodeInsightFixtureTestCase {
  public void testTheHintSkipsAClassWhoseStaticMethodTakesAnotherParameterType() {
    addHelperClass("""
                     package a.b.c;
                     public final class AA {
                       public static <T> T test(String t) { return null; }
                     }
                     """);
    assertHintIsNotShown("""
                           public final class A {
                             static void main() {
                               String a3 = A<caret>A.test(1);
                             }
                           }
                           """);
  }

  public void testTheHintShowsAClassWhoseStaticMethodMatches() {
    addHelperClass("""
                     package a.b.c;
                     public final class AA {
                       public static <T> T test(String t) { return null; }
                     }
                     """);
    assertHintIsShown("""
                        public final class A {
                          static void main() {
                            String a3 = A<caret>A.test("x");
                          }
                        }
                        """);
  }

  public void testTheHintSkipsAClassWhoseStaticMethodTakesAnotherArgumentCount() {
    addHelperClass("""
                     package a.b.c;
                     public final class AA {
                       public static String test(String t) { return null; }
                     }
                     """);
    assertHintIsNotShown("""
                           public final class A {
                             static void main() {
                               String a3 = A<caret>A.test("x", "y");
                             }
                           }
                           """);
  }

  public void testTheHintShowsAClassWithoutTheCalledMethod() {
    addHelperClass("""
                     package a.b.c;
                     public final class AA {
                       public static String other(String t) { return null; }
                     }
                     """);
    // the user can create the method later, so the class stays
    assertHintIsShown("""
                        public final class A {
                          static void main() {
                            String a3 = A<caret>A.test(1);
                          }
                        }
                        """);
  }

  public void testTheHintShowsAClassUsedAsAType() {
    addHelperClass("""
                     package a.b.c;
                     public final class AA {
                       public static String test(String t) { return null; }
                     }
                     """);
    assertHintIsShown("""
                        public final class A {
                          static void main() {
                            A<caret>A a = null;
                          }
                        }
                        """);
  }

  public void testTheQuickFixKeepsAClassTheHintSkips() {
    addHelperClass("""
                     package a.b.c;
                     public final class AA {
                       public static <T> T test(String t) { return null; }
                     }
                     """);
    @Language("JAVA")
    String text = """
      public final class A {
        static void main() {
          String a3 = A<caret>A.test(1);
        }
      }
      """;
    assertEquals(ImportClassFixBase.Result.POPUP_NOT_SHOWN, doFix(text));
    assertSize(1, createFix().getClassesToImport());
  }

  private void addHelperClass(@Language("JAVA") @NotNull String text) {
    myFixture.addClass(text);
  }

  private void assertHintIsShown(@Language("JAVA") @NotNull String text) {
    assertEquals(ImportClassFixBase.Result.POPUP_SHOWN, doFix(text));
  }

  private void assertHintIsNotShown(@Language("JAVA") @NotNull String text) {
    assertEquals(ImportClassFixBase.Result.POPUP_NOT_SHOWN, doFix(text));
  }

  private ImportClassFixBase.@NotNull Result doFix(@Language("JAVA") @NotNull String text) {
    myFixture.configureByText("A.java", text);
    return createFix().doFix(myFixture.getEditor(), true, true, false);
  }

  private @NotNull ImportClassFix createFix() {
    PsiReference reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
    assertInstanceOf(reference, PsiJavaCodeReferenceElement.class);
    PsiJavaCodeReferenceElement referenceElement = (PsiJavaCodeReferenceElement)reference;
    try {
      return ApplicationManager.getApplication()
        .executeOnPooledThread(() -> ReadAction.computeBlocking(() -> new ImportClassFix(referenceElement)))
        .get();
    }
    catch (InterruptedException | ExecutionException e) {
      throw new AssertionError(e);
    }
  }
}
