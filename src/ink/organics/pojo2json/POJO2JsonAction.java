package ink.organics.pojo2json;

import com.google.gson.GsonBuilder;
import com.intellij.notification.*;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NonNls;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class POJO2JsonAction extends AnAction {
    private static final NotificationGroup notificationGroup = new NotificationGroup("pojo2json.NotificationGroup", NotificationDisplayType.BALLOON, true);

    @NonNls
    private static final Map<String, Object> normalTypes = new HashMap<>();

    private static final GsonBuilder gsonBuilder = (new GsonBuilder()).setPrettyPrinting();

    private static final BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

    static {
        LocalDateTime now = LocalDateTime.now();
        String dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Long nowMillis = System.currentTimeMillis();
        normalTypes.put("Boolean", "|" + Boolean.valueOf(false) + "|");
        normalTypes.put("Float", "|" + zero + "|");
        normalTypes.put("Double", "|" + zero + "|");
        normalTypes.put("BigDecimal", "|" + zero + "|");
        normalTypes.put("Number", "|" + Integer.valueOf(0) + "|");
        normalTypes.put("CharSequence", "");
        normalTypes.put("Date", "|" + nowMillis + "|");
        normalTypes.put("Temporal", "|" + nowMillis + "|");
        normalTypes.put("LocalDateTime", "|" + nowMillis + "|");
        normalTypes.put("LocalDate", "|" + now.toLocalDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "|");
        normalTypes.put("LocalTime", "|" + now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "|");
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Editor editor = (Editor) e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = (PsiFile) e.getData(CommonDataKeys.PSI_FILE);
        Project project = e.getProject();
        PsiElement elementAt = psiFile.findElementAt(editor.getCaretModel().getOffset());
        PsiClass selectedClass = (PsiClass) PsiTreeUtil.getContextOfType(elementAt, new Class[]{PsiClass.class});
        try {
            Map<String, Object> kv = getFields(selectedClass);
            String json = gsonBuilder.create().toJson(kv);
            json = json.replaceAll("@", "\", //")
                    .replaceAll("#\",", "")
                    .replaceAll("#\"", "")
                    .replaceAll("\"\\|", "")
                    .replaceAll("\\|\"", "");

            StringSelection selection = new StringSelection(json);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(selection, selection);
            String message = "Convert " + selectedClass.getName() + " to JSON success, copied to clipboard.";
            Notification success = notificationGroup.createNotification(message, NotificationType.INFORMATION);
            Notifications.Bus.notify(success, project);
        } catch (KnownException ex) {
            Notification warn = notificationGroup.createNotification(ex.getMessage(), NotificationType.WARNING);
            Notifications.Bus.notify(warn, project);
        } catch (Exception ex) {
            Notification error = notificationGroup.createNotification("Convert to JSON failed.", NotificationType.ERROR);
            Notifications.Bus.notify(error, project);
        }
    }

    private static Map<String, Object> getFields(PsiClass psiClass) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (psiClass == null) {
            return map;
        }
        for (PsiField field : psiClass.getAllFields()) {
            if ("serialVersionUID".equals(field.getName())) {
                continue;
            }
            map.put(field.getName(), typeResolve(field.getType(), 0, field));
        }
        return map;
    }

    private static Object typeResolve(PsiType type, int level, PsiField parentField) {
        level = ++level;
        String canonicalText = type.getCanonicalText();
        if (canonicalText.contains(".")) {
            canonicalText = canonicalText.substring(canonicalText.lastIndexOf(".") + 1);
        }
        String fieldDesc = getFieldDesc(parentField);
        fieldDesc = "@" + canonicalText + ":" + fieldDesc + "#";
        if (type instanceof com.intellij.psi.PsiPrimitiveType) {
            return getDefaultValue(type) + fieldDesc;
        }
        if (type instanceof com.intellij.psi.PsiArrayType) {
            List<Object> list = new ArrayList();
            PsiType deepType = type.getDeepComponentType();
            list.add(typeResolve(deepType, level, parentField));
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        PsiClass psiClass = PsiUtil.resolveClassInClassTypeOnly(type);
        if (psiClass == null) {
            return map;
        }
        if (psiClass.isEnum()) {
            for (PsiField field : psiClass.getFields()) {
                if (field instanceof com.intellij.psi.PsiEnumConstant) {
                    return field.getName() + fieldDesc;
                }
            }
            return "";
        }
        List<String> fieldTypeNames = new ArrayList<>();
        PsiType[] types = type.getSuperTypes();
        fieldTypeNames.add(type.getPresentableText());
        fieldTypeNames.addAll((Collection<? extends String>) Arrays.<PsiType>stream(types).map(PsiType::getPresentableText).collect(Collectors.toList()));
        if (fieldTypeNames.stream().anyMatch(s -> (s.startsWith("Collection") || s.startsWith("Iterable")))) {
            List<Object> list = new ArrayList();
            PsiType deepType = PsiUtil.extractIterableTypeParameter(type, false);
            list.add(typeResolve(deepType, level, parentField));
            return list;
        }
        List<String> retain = new ArrayList<>(fieldTypeNames);
        retain.retainAll(normalTypes.keySet());
        if (!retain.isEmpty()) {
            return normalTypes.get(retain.get(0)) + fieldDesc;
        }
        if (level > 500) {
            throw new KnownException("This class reference level exceeds maximum limit or has nested references!");
        }
        for (PsiField field : psiClass.getAllFields()) {
            map.put(field.getName(), typeResolve(field.getType(), level, field));
        }
        return map;
    }

    public static Object getDefaultValue(PsiType type) {
        if (!(type instanceof com.intellij.psi.PsiPrimitiveType)) {
            return null;
        }
        switch (type.getCanonicalText()) {
            case "boolean":
                return "|" + Boolean.valueOf(false) + "|";
            case "byte":
                return "|" + Byte.valueOf((byte) 0) + "|";
            case "char":
                return "|" + Character.valueOf('0') + "|";
            case "short":
                return "|" + Short.valueOf((short) 0) + "|";
            case "int":
                return "|" + Integer.valueOf(0) + "|";
            case "long":
                return "|" + Long.valueOf(0L) + "|";
            case "float":
                return "|" + zero + "|";
            case "double":
                return "|" + zero + "|";
        }
        return null;
    }

    public static String getFieldDesc(PsiField psiField) {
        if (psiField == null) {
            return "";
        }
        PsiDocComment docComment = psiField.getDocComment();
        if (docComment != null) {
            return getComment(docComment);
        }
        return "";
    }

    public static String getComment(PsiDocComment docComment) {
        StringBuilder sb = new StringBuilder();
        if (docComment != null) {
            for (PsiElement element : docComment.getChildren()) {
                sb.append(element.getText().replaceAll("[/* \n//@#(@see)|]+", ""));
            }
        }
        return sb.toString();
    }
}
