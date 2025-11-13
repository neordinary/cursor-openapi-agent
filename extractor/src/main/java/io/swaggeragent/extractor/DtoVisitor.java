package io.swaggeragent.extractor;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import io.swaggeragent.extractor.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;

/**
 * AST를 순회하며 DTO 클래스와 필드 정보를 추출하는 Visitor
 */
@AllArgsConstructor
public class DtoVisitor extends VoidVisitorAdapter<Void> {
    private final Path filePath;
    private final Set<DtoInfo> dtoClasses;
    private final String projectRoot;
    private final JavaParser javaParser;

    @Override
    public void visit(ClassOrInterfaceDeclaration n, Void arg) {
        DtoInfo dto = extractDto(n);
        if (dto != null) {
            dtoClasses.add(dto);
        }
        // 하위 노드들도 방문
        super.visit(n, arg);
    }

    @Override
    public void visit(RecordDeclaration n, Void arg) {
        DtoInfo dto = extractRecordDto(n);
        if (dto != null) {
            dtoClasses.add(dto);
        }
        // 하위 노드들도 방문
        super.visit(n, arg);
    }

    /**
     * DTO 클래스에서 정보 추출
     */
    private DtoInfo extractDto(ClassOrInterfaceDeclaration n) {
        // 기본 DTO 정보 추출
        String className = n.getNameAsString();
        String filePathStr = filePath.toString();
        
        DtoInfo dto = DtoInfo.builder()
            .className(className)
            .filePath(filePathStr)
            .build();
        
        // 필드 정보 추출
        List<FieldInfo> fields = n.getFields().stream()
            .map(this::extractField)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        dto.setFields(fields);
        return dto;
    }

    /**
     * Record 타입 DTO에서 정보 추출
     */
    private DtoInfo extractRecordDto(RecordDeclaration n) {
        // 기본 DTO 정보 추출
        String className = n.getNameAsString();
        String filePathStr = filePath.toString();
        
        DtoInfo dto = DtoInfo.builder()
            .className(className)
            .filePath(filePathStr)
            .build();
        
        // Record 컴포넌트 정보 추출 (Record의 컴포넌트는 getParameters()로 접근)
        List<FieldInfo> fields = n.getParameters().stream()
            .map(this::extractRecordComponent)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        dto.setFields(fields);
        return dto;
    }

    /**
     * DTO 필드에서 정보 추출
     * - 필드명, 타입, 검증 어노테이션, 설명, 필수 여부 등을 추출
     * - 필드 타입이 DTO인 경우 중첩된 DTO도 탐색
     */
    private FieldInfo extractField(com.github.javaparser.ast.body.FieldDeclaration field) {
        if (field.getVariables().isEmpty()) {
            return null;
        }
        
        com.github.javaparser.ast.body.VariableDeclarator variable = field.getVariables().get(0);
        String fieldName = variable.getNameAsString();
        String originalType = field.getElementType().toString();
        String fieldType = TypeParser.parseType(originalType, TypeParser.ParseMode.FIELD_NORMALIZATION).getBaseType();
        
        // 필드 타입이 DTO인지 확인하고 중첩된 DTO 탐색
        String className = TypeParser.parseType(originalType, TypeParser.ParseMode.CLASS_NAME_ONLY).getBaseType();
        if (isDtoClassName(className)) {
            addNestedDtoIfNotExists(className);
        }
        
        // 검증 어노테이션 추출 (확장된 목록)
        List<String> validationAnnotations = field.getAnnotations().stream()
            .map(AnnotationExpr::getNameAsString)
            .filter(this::isValidationAnnotation)
            .collect(Collectors.toList());
        
        // 필수 여부 확인 (다양한 어노테이션 체크)
        boolean required = isFieldRequired(field);
        
        // 필드 설명 추출 (Javadoc, @Schema, @ApiModelProperty 등)
        String description = extractFieldDescription(field);
        
        return FieldInfo.builder()
            .name(fieldName)
            .type(fieldType)
            .validationAnnotations(validationAnnotations.toArray(new String[0]))
            .description(description)
            .required(required)
            .build();
    }

    /**
     * Record 컴포넌트에서 정보 추출
     * - 컴포넌트명, 타입, 검증 어노테이션, 설명, 필수 여부 등을 추출
     * - 컴포넌트 타입이 DTO인 경우 중첩된 DTO도 탐색
     */
    private FieldInfo extractRecordComponent(Parameter component) {
        String componentName = component.getNameAsString();
        String originalType = component.getType().toString();
        String fieldType = TypeParser.parseType(originalType, TypeParser.ParseMode.FIELD_NORMALIZATION).getBaseType();
        
        // 컴포넌트 타입이 DTO인지 확인하고 중첩된 DTO 탐색
        String className = TypeParser.parseType(originalType, TypeParser.ParseMode.CLASS_NAME_ONLY).getBaseType();
        if (isDtoClassName(className)) {
            addNestedDtoIfNotExists(className);
        }
        
        // 검증 어노테이션 추출 (확장된 목록)
        List<String> validationAnnotations = component.getAnnotations().stream()
            .map(AnnotationExpr::getNameAsString)
            .filter(this::isValidationAnnotation)
            .collect(Collectors.toList());
        
        // 필수 여부 확인 (다양한 어노테이션 체크)
        boolean required = isRecordComponentRequired(component);
        
        // 필드 설명 추출 (Javadoc, @Schema, @ApiModelProperty 등)
        String description = extractRecordComponentDescription(component);
        
        return FieldInfo.builder()
            .name(componentName)
            .type(fieldType)
            .validationAnnotations(validationAnnotations.toArray(new String[0]))
            .description(description)
            .required(required)
            .build();
    }

    /**
     * 검증 어노테이션인지 확인
     */
    private boolean isValidationAnnotation(String annotationName) {
        return annotationName.equals("Valid") || 
               annotationName.equals("NotNull") || 
               annotationName.equals("NotBlank") ||
               annotationName.equals("NotEmpty") ||
               annotationName.equals("Size") || 
               annotationName.equals("Min") ||
               annotationName.equals("Max") ||
               annotationName.equals("Email") || 
               annotationName.equals("Pattern") ||
               annotationName.equals("DecimalMin") ||
               annotationName.equals("DecimalMax") ||
               annotationName.equals("Digits") ||
               annotationName.equals("Future") ||
               annotationName.equals("Past") ||
               annotationName.equals("AssertTrue") ||
               annotationName.equals("AssertFalse");
    }

    /**
     * 필드가 필수인지 확인
     */
    private boolean isFieldRequired(com.github.javaparser.ast.body.FieldDeclaration field) {
        return field.getAnnotations().stream()
            .anyMatch(ann -> {
                String name = ann.getNameAsString();
                return name.equals("NotNull") || 
                       name.equals("NotBlank") || 
                       name.equals("NotEmpty") ||
                       name.equals("Required");
            });
    }

    /**
     * 필드 설명 추출
     * - Javadoc, @Schema, @ApiModelProperty 어노테이션에서 설명 추출
     */
    private String extractFieldDescription(com.github.javaparser.ast.body.FieldDeclaration field) {
        // 1. @Schema 어노테이션에서 description 추출
        Optional<String> schemaDescription = field.getAnnotations().stream()
            .filter(ann -> ann.getNameAsString().equals("Schema"))
            .findFirst()
            .map(ann -> extractAnnotationValue(ann, "description"));
        
        if (schemaDescription.isPresent() && !schemaDescription.get().isEmpty()) {
            return schemaDescription.get();
        }
        
        // 2. @ApiModelProperty 어노테이션에서 value 추출
        Optional<String> apiModelDescription = field.getAnnotations().stream()
            .filter(ann -> ann.getNameAsString().equals("ApiModelProperty"))
            .findFirst()
            .map(ann -> extractAnnotationValue(ann, "value"));
        
        if (apiModelDescription.isPresent() && !apiModelDescription.get().isEmpty()) {
            return apiModelDescription.get();
        }
        
        // 3. Javadoc에서 설명 추출
        return field.getJavadoc()
            .map(javadoc -> javadoc.getDescription().toText().trim())
            .filter(desc -> !desc.isEmpty())
            .orElse("");
    }

    /**
     * Record 컴포넌트가 필수인지 확인
     */
    private boolean isRecordComponentRequired(com.github.javaparser.ast.body.Parameter component) {
        return component.getAnnotations().stream()
            .anyMatch(ann -> {
                String name = ann.getNameAsString();
                return name.equals("NotNull") || 
                       name.equals("NotBlank") || 
                       name.equals("NotEmpty") ||
                       name.equals("Required");
            });
    }

    /**
     * Record 컴포넌트 설명 추출
     * - Javadoc, @Schema, @ApiModelProperty 어노테이션에서 설명 추출
     */
    private String extractRecordComponentDescription(com.github.javaparser.ast.body.Parameter component) {
        // 1. @Schema 어노테이션에서 description 추출
        Optional<String> schemaDescription = component.getAnnotations().stream()
            .filter(ann -> ann.getNameAsString().equals("Schema"))
            .findFirst()
            .map(ann -> extractAnnotationValue(ann, "description"));
        
        if (schemaDescription.isPresent() && !schemaDescription.get().isEmpty()) {
            return schemaDescription.get();
        }
        
        // 2. @ApiModelProperty 어노테이션에서 value 추출
        Optional<String> apiModelDescription = component.getAnnotations().stream()
            .filter(ann -> ann.getNameAsString().equals("ApiModelProperty"))
            .findFirst()
            .map(ann -> extractAnnotationValue(ann, "value"));
        
        if (apiModelDescription.isPresent() && !apiModelDescription.get().isEmpty()) {
            return apiModelDescription.get();
        }
        
        // 3. Comment에서 설명 추출 (Parameter는 getComment() 사용)
        return component.getComment()
            .map(comment -> comment.getContent().trim())
            .filter(desc -> !desc.isEmpty())
            .orElse("");
    }

    /**
     * 어노테이션에서 특정 속성값 추출
     */
    private String extractAnnotationValue(AnnotationExpr annotation, String attributeName) {
        if (annotation.isNormalAnnotationExpr()) {
            com.github.javaparser.ast.expr.NormalAnnotationExpr normalAnn = 
                annotation.asNormalAnnotationExpr();
            return normalAnn.getPairs().stream()
                .filter(pair -> pair.getNameAsString().equals(attributeName))
                .findFirst()
                .map(pair -> pair.getValue().toString().replaceAll("\"", ""))
                .orElse("");
        }
        return "";
    }

    /**
     * 클래스명이 DTO 패턴인지 확인
     */
    private boolean isDtoClassName(String className) {
        return className.endsWith("Dto") || 
               className.endsWith("DTO") || 
               className.endsWith("Req") || 
               className.endsWith("Res") || 
               className.endsWith("Request") || 
               className.endsWith("Response");
    }

    /**
     * 중첩된 DTO 클래스가 이미 존재하지 않으면 추가하고 재귀적으로 처리
     */
    private void addNestedDtoIfNotExists(String className) {
        // 이미 존재하는지 확인
        boolean exists = dtoClasses.stream()
            .anyMatch(dto -> dto.getClassName().equals(className));
        
        if (!exists) {
            // 파일 경로 찾기
            String filePath = findDtoFileByClassName(className);
            
            if (filePath != null && Files.exists(Paths.get(filePath))) {
                // DTO 파일을 찾았으면 파싱하여 처리
                try {
                    Path path = Paths.get(filePath);
                    CompilationUnit cu = javaParser.parse(path).getResult().orElse(null);
                    if (cu != null) {
                        // 재귀적으로 DTO Visitor를 사용하여 중첩된 DTO도 탐색
                        cu.accept(new DtoVisitor(path, dtoClasses, projectRoot, javaParser), null);
                    }
                } catch (Exception e) {
                    System.err.println("중첩된 DTO 파일 처리 중 오류: " + filePath + " - " + e.getMessage());
                }
            } else {
                // 파일을 찾지 못한 경우 빈 DTO 정보만 추가
                String estimatedPath = "src/main/java/com/example/dto/" + className + ".java";
                DtoInfo dto = DtoInfo.builder()
                    .className(className)
                    .fields(new ArrayList<>())
                    .existingAnnotations(new HashMap<>())
                    .filePath(estimatedPath)
                    .build();
                dtoClasses.add(dto);
            }
        }
    }

    /**
     * 클래스명으로 파일 검색
     */
    private String findDtoFileByClassName(String className) {
        try {
            Path projectRootPath = Paths.get(projectRoot);
            return Files.walk(projectRootPath)
                .filter(path -> path.getFileName().toString().equals(className + ".java"))
                .findFirst()
                .map(Path::toString)
                .orElse(null);
        } catch (IOException e) {
            System.err.println("클래스명으로 파일 검색 중 오류: " + e.getMessage());
            return null;
        }
    }
}
