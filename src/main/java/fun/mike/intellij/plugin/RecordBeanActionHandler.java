package fun.mike.intellij.plugin;

import com.intellij.lang.LanguageCodeInsightActionHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class RecordBeanActionHandler implements LanguageCodeInsightActionHandler {
    private static final String JSON_PROPERTY_ANNOTATION = "com.fasterxml.jackson.annotation.JsonProperty";

    @Override
    public boolean isValidFor(Editor editor, PsiFile file) {
        if (!(file instanceof PsiJavaFile)) {
            return false;
        }

        final Project project = editor.getProject();

        if (project == null) {
            return false;
        }

        return ClassLocator.locateStaticOrTopLevelClass(editor, file) != null;
    }

    @Override
    public void invoke(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        Messenger msg = new Messenger(project);

        try {
            PsiClass rootClass = ClassLocator.locateClass(editor, file);

            if (rootClass == null) {
                return;
            }

            // Delete inner classes
            List<PsiClass> innerClasses = Arrays.asList(rootClass.getInnerClasses());

            innerClasses.forEach(PsiElement::delete);

            // Delete methods
            List<PsiMethod> methods = Arrays.asList(rootClass.getMethods());

            methods.forEach(PsiElement::delete);

            // Generate getters
            List<PsiField> fields = Arrays.asList(rootClass.getFields());

            fields.stream()
                    .map(field -> generateGetter(project, field))
                    .forEach(rootClass::add);

            rootClass.add(generateConstructor(project, rootClass, fields));
            rootClass.add(generateEquals(project, rootClass, fields));
            rootClass.add(generateHashCode(project, rootClass, fields));
            rootClass.add(generateToString(project, rootClass, fields));

            PsiClass builderClass = generateBuilderClass(project, rootClass);

            fields.stream()
                    .map(field -> generateField(project, field))
                    .forEach(builderClass::add);

            fields.stream()
                    .map(field -> generateBuilderMethod(project, builderClass, field))
                    .forEach(builderClass::add);

            builderClass.add(generateBuildMethod(project, rootClass, fields));

            rootClass.add(generateNewBuilderMethod(project, rootClass, builderClass));

            // Shorten class names
            JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(project);

            codeStyleManager.shortenClassReferences(rootClass);
        } catch (Exception ex) {
            msg.show(ex.getClass().getName() + ": " + ex.getMessage());
        }
    }

    private PsiMethod generateGetter(Project project,
                                     PsiField field) {
        PsiElementFactory elementFactory =
                JavaPsiFacade.getInstance(project).getElementFactory();

        PsiMethod method = elementFactory.createMethod(field.getName(), field.getType());
        PsiUtil.setModifierProperty(method, PsiModifier.PUBLIC, true);

        PsiStatement returnStatement = elementFactory
                .createStatementFromText("return " + field.getName() + ";", method);

        method.getBody().add(returnStatement);

        method.getModifierList().addAnnotation(JSON_PROPERTY_ANNOTATION);

        return method;
    }

    private PsiMethod generateConstructor(Project project,
                                          PsiClass clazz,
                                          List<PsiField> fields) {
        PsiElementFactory elementFactory =
                JavaPsiFacade.getInstance(project).getElementFactory();

        PsiMethod constructor = elementFactory.createConstructor(clazz.getName());

        fields.forEach(field -> {
            PsiParameter parameter = elementFactory.createParameter(field.getName(), field.getType());
            constructor.getParameterList().add(parameter);
        });

        fields.forEach(field -> {
            PsiStatement assignment = elementFactory
                    .createStatementFromText("this." + field.getName() + " = " + field.getName() + ";",
                                             constructor);

            constructor.getBody().add(assignment);
        });

        String valueList = fields.stream()
                .map(field -> '"' + field.getName() + '"')
                .collect(Collectors.joining(", "));

        constructor.getModifierList().addAnnotation("java.beans.ConstructorProperties({" + valueList + "})");

        return constructor;
    }

    private PsiMethod generateHashCode(Project project,
                                       PsiClass clazz,
                                       List<PsiField> fields) {
        PsiElementFactory elementFactory = JavaPsiFacade.getInstance(project).getElementFactory();

        PsiMethod method = elementFactory.createMethod("hashCode", PsiType.INT);

        PsiUtil.setModifierProperty(method, PsiModifier.PUBLIC, true);

        String fieldList = fields.stream()
                .map(PsiField::getName)
                .collect(Collectors.joining(", "));

        PsiStatement returnStatement = elementFactory
                .createStatementFromText("return java.util.Objects.hash(" + fieldList + ");",
                                         method);

        method.getBody().add(returnStatement);

        method.getModifierList().addAnnotation("Override");

        return method;
    }

    private PsiMethod generateEquals(Project project,
                                     PsiClass clazz,
                                     List<PsiField> fields) {
        String className = clazz.getName();
        PsiElementFactory elementFactory =
                JavaPsiFacade.getInstance(project).getElementFactory();

        PsiManager manager = PsiManager.getInstance(project);

        PsiMethod method = elementFactory.createMethod("equals", PsiType.BOOLEAN);

        PsiClassType objectType = PsiType.getJavaLangObject(manager, GlobalSearchScope.EMPTY_SCOPE);
        PsiParameter parameter = elementFactory.createParameter("o", objectType);

        method.getParameterList().add(parameter);

        PsiUtil.setModifierProperty(method, PsiModifier.PUBLIC, true);

        method.getModifierList().addAnnotation("Override");

        // Reference equality check
        PsiStatement referenceEqualityCheckStatement = elementFactory
                .createStatementFromText("if (this == o) return true;", method);

        method.getBody().add(referenceEqualityCheckStatement);

        // Same class check
        PsiStatement sameClassCheckStatement = elementFactory
                .createStatementFromText("if (o == null || getClass() != o.getClass()) return false;", method);

        method.getBody().add(sameClassCheckStatement);

        // Cast
        String castedVariableName = className.substring(0, 1).toLowerCase() + className.substring(1);

        PsiStatement castStatement = elementFactory
                .createStatementFromText(className + " " + castedVariableName + " = (" + className + ") o;", method);

        method.getBody().add(castStatement);

        // Field checks
        String fieldExpressions = fields.stream()
                .map(field -> {
                    String fieldName = field.getName();
                    return "java.util.Objects.equals(" + fieldName + ", " + castedVariableName + "." + fieldName + ")";
                })
                .collect(Collectors.joining(" &&\n"));

        PsiStatement fieldCheckStatement = elementFactory
                .createStatementFromText("return " + fieldExpressions + ";", method);

        method.getBody().add(fieldCheckStatement);

        return method;
    }


    private PsiMethod generateToString(Project project,
                                       PsiClass clazz,
                                       List<PsiField> fields) {
        String className = clazz.getName();

        PsiElementFactory elementFactory = JavaPsiFacade.getInstance(project).getElementFactory();

        PsiManager manager = PsiManager.getInstance(project);

        PsiClassType stringType = PsiType.getJavaLangString(manager, GlobalSearchScope.EMPTY_SCOPE);

        PsiMethod method = elementFactory.createMethod("toString", stringType);

        PsiUtil.setModifierProperty(method, PsiModifier.PUBLIC, true);

        method.getModifierList().addAnnotation("Override");

        // Field checks
        String concatenations = fields.stream()
                .map(field -> {
                    String fieldName = field.getName();
                    return fieldName + "=" + fieldName;
                })
                .collect(Collectors.joining(", \" +\n\"")); // TODO: Surround strings with quotes

        PsiStatement fieldCheckStatement = elementFactory
                .createStatementFromText("return \"" + className + "{\" +\n\"" + concatenations + "}\";",
                                         method);

        method.getBody().add(fieldCheckStatement);

        return method;
    }

    private PsiClass generateBuilderClass(Project project, PsiClass clazz) {
        PsiElementFactory elementFactory = JavaPsiFacade.getInstance(project).getElementFactory();

        PsiClass builderClass = (PsiClass) clazz.add(elementFactory.createClass("Builder"));

        PsiUtil.setModifierProperty(builderClass, PsiModifier.STATIC, true);
        PsiUtil.setModifierProperty(builderClass, PsiModifier.FINAL, true);

        return builderClass;
    }

    private static PsiField generateField(Project project, PsiField rootField) {
        PsiElementFactory elementFactory = JavaPsiFacade.getInstance(project).getElementFactory();
        PsiField field = elementFactory.createField(rootField.getName(), rootField.getType());

        PsiUtil.setModifierProperty(field, PsiModifier.PRIVATE, true);

        return field;
    }

    private static PsiMethod generateBuilderMethod(Project project, PsiClass builderClass, PsiField field) {
        String fieldName = field.getName();
        PsiType fieldType = field.getType();

        PsiElementFactory elementFactory = JavaPsiFacade.getInstance(project).getElementFactory();

        PsiMethod method = elementFactory.createMethod(fieldName, PsiType.getTypeByName(builderClass.getQualifiedName(),
                                                                                        project,
                                                                                        GlobalSearchScope.EMPTY_SCOPE));

        method.getParameterList().add(elementFactory.createParameter("val", fieldType));

        method.getBody().add(elementFactory.createStatementFromText(fieldName + " = val;", method));

        method.getBody().add(elementFactory.createStatementFromText("return this;", method));

        PsiUtil.setModifierProperty(method, PsiModifier.PUBLIC, true);

        return method;
    }

    private PsiElement generateBuildMethod(Project project, PsiClass rootClass, List<PsiField> fields) {
        PsiElementFactory elementFactory = JavaPsiFacade.getInstance(project).getElementFactory();

        PsiMethod method = elementFactory.createMethod("build",
                                                       PsiType.getTypeByName(rootClass.getQualifiedName(),
                                                                             project,
                                                                             GlobalSearchScope.EMPTY_SCOPE));

        String fieldList = fields.stream()
                .map(PsiField::getName)
                .collect(Collectors.joining(",\n"));

        method.getBody().add(elementFactory.createStatementFromText("return new " + rootClass.getName() + "(" + fieldList + ");", method));

        PsiUtil.setModifierProperty(method, PsiModifier.PUBLIC, true);

        return method;
    }


    private PsiElement generateNewBuilderMethod(Project project, PsiClass rootClass, PsiClass builderClass) {
        PsiElementFactory elementFactory = JavaPsiFacade.getInstance(project).getElementFactory();

        PsiMethod method = elementFactory.createMethod("newBuilder",
                                                       PsiType.getTypeByName(builderClass.getQualifiedName(),
                                                                             project,
                                                                             GlobalSearchScope.EMPTY_SCOPE));

        method.getBody().add(elementFactory.createStatementFromText("return new " + builderClass.getName() + "();", method));

        PsiUtil.setModifierProperty(method, PsiModifier.PUBLIC, true);
        PsiUtil.setModifierProperty(method, PsiModifier.STATIC, true);

        return method;
    }
}
