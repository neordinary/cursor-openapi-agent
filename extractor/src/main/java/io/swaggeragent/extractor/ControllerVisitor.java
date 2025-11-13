package io.swaggeragent.extractor;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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
 * AST를 순회하며 Controller 클래스와 메서드 정보를 추출하는 Visitor
 */
@AllArgsConstructor
public class ControllerVisitor extends VoidVisitorAdapter<Void> {
    private final List<ControllerInfo> controllers;
    private final Set<DtoInfo> dtoClasses;
    private final String projectRoot;
    private final JavaParser javaParser;

    @Override
    public void visit(ClassOrInterfaceDeclaration n, Void arg) {
        if (isController(n)) {
            ControllerInfo controller = extractController(n);
            if (controller != null) {
                controllers.add(controller);
            }
        }
        // 하위 노드들도 방문
        super.visit(n, arg);
    }

    private boolean isController(ClassOrInterfaceDeclaration n) {
        return n.getAnnotations().stream()
            .anyMatch(ann -> ann.getNameAsString().equals("RestController") || 
                           ann.getNameAsString().equals("Controller"));
    }

    /**
     * Controller 클래스에서 정보 추출
     */
    private ControllerInfo extractController(ClassOrInterfaceDeclaration n) {
        // 기본 Controller 정보 추출
        String className = n.getNameAsString();
        String requestMapping = extractRequestMapping(n);
        
        ControllerInfo controller = ControllerInfo.builder()
            .className(className)
            .requestMapping(requestMapping)
            .build();
        
        // HTTP 매핑 메서드들 추출
        List<MethodInfo> methods = n.getMethods().stream()
            .filter(this::isMappingMethod)  // HTTP 매핑 어노테이션이 있는 메서드만 필터링
            .map(this::extractMethod)       // 각 메서드에서 정보 추출
            .filter(Objects::nonNull)       // null이 아닌 메서드만 유지
            .collect(Collectors.toList());
        
        controller.setMethods(methods);
        
        // 컨트롤러에서 사용되는 DTO 클래스들 감지
        detectRelatedDtos(n);
        
        return controller;
    }

    /**
     * 클래스의 @RequestMapping 어노테이션 값 추출
     */
    private String extractRequestMapping(ClassOrInterfaceDeclaration n) {
        return n.getAnnotations().stream()
            .filter(ann -> ann.getNameAsString().equals("RequestMapping"))
            .findFirst()
            .map(this::extractAnnotationValue)
            .orElse("");
    }

    /**
     * 어노테이션의 value 값 추출
     */
    private String extractAnnotationValue(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            // @RequestMapping("/api/users") 형태
            return annotation.asSingleMemberAnnotationExpr()
                .getMemberValue()
                .asStringLiteralExpr()
                .getValue();
        } else if (annotation.isNormalAnnotationExpr()) {
            // @RequestMapping(value="/api/users") 형태
            return annotation.asNormalAnnotationExpr()
                .getPairs().stream()
                .filter(pair -> pair.getNameAsString().equals("value"))
                .findFirst()
                .map(pair -> pair.getValue().asStringLiteralExpr().getValue())
                .orElse("");
        }
        return "";
    }

    /**
     * 주어진 메서드가 HTTP 매핑 메서드인지 확인
     */
    private boolean isMappingMethod(MethodDeclaration n) {
        return n.getAnnotations().stream()
            .anyMatch(ann -> {
                String name = ann.getNameAsString();
                return name.equals("GetMapping") || name.equals("PostMapping") || 
                       name.equals("PutMapping") || name.equals("DeleteMapping") ||
                       name.equals("RequestMapping");
            });
    }

    /**
     * HTTP 매핑 메서드에서 정보 추출
     */
    private MethodInfo extractMethod(MethodDeclaration n) {
        // 기본 메서드 정보 추출
        String methodName = n.getNameAsString();
        String httpMethod = extractHttpMethod(n);
        String path = extractMethodPath(n);
        
        MethodInfo method = MethodInfo.builder()
            .methodName(methodName)
            .httpMethod(httpMethod)
            .path(path)
            .lineNumber(n.getBegin().map(pos -> pos.line).orElse(0))
            .returnType(extractReturnType(n))
            .parameters(extractParameters(n))
            .exceptions(extractExceptions(n))
            .build();
        
        return method;
    }

    /**
     * 메서드의 HTTP 메서드 추출
     */
    private String extractHttpMethod(MethodDeclaration n) {
        return n.getAnnotations().stream()
            .map(AnnotationExpr::getNameAsString)
            .filter(name -> name.endsWith("Mapping"))
            .map(name -> name.replace("Mapping", "").toUpperCase())
            .findFirst()
            .orElse("GET");
    }

    /**
     * 메서드의 경로 추출
     */
    private String extractMethodPath(MethodDeclaration n) {
        return n.getAnnotations().stream()
            .filter(ann -> ann.getNameAsString().endsWith("Mapping"))
            .findFirst()
            .map(this::extractAnnotationValue)
            .orElse("");
    }

    private String extractReturnType(MethodDeclaration n) {
        return n.getType().toString();
    }

    /**
     * 메서드의 파라미터 목록 추출
     */
    private List<ParameterInfo> extractParameters(MethodDeclaration n) {
        return n.getParameters().stream()
            .map(param -> {
                String name = param.getNameAsString();
                String type = param.getType().toString();
                String in = determineParameterIn(param);        // 파라미터 위치 결정
                boolean required = isParameterRequired(param);  // 필수 여부 결정
                
                ParameterInfo paramInfo = ParameterInfo.builder()
                    .name(name)
                    .type(type)
                    .in(in)
                    .required(required)
                    .validationAnnotations(extractValidationAnnotations(param))
                    .build();
                return paramInfo;
            })
            .collect(Collectors.toList());
    }

    /**
     * 파라미터의 위치 결정
     */
    private String determineParameterIn(com.github.javaparser.ast.body.Parameter param) {
        // @RequestBody가 있으면 body로 분류
        boolean hasRequestBody = param.getAnnotations().stream()
            .anyMatch(ann -> ann.getNameAsString().equals("RequestBody"));
        
        if (hasRequestBody) {
            return "body";
        }
        
        // @PathVariable이 있으면 path로 분류
        boolean hasPathVariable = param.getAnnotations().stream()
            .anyMatch(ann -> ann.getNameAsString().equals("PathVariable"));
        
        if (hasPathVariable) {
            return "path";
        }
        
        // @RequestHeader가 있으면 header로 분류
        boolean hasRequestHeader = param.getAnnotations().stream()
            .anyMatch(ann -> ann.getNameAsString().equals("RequestHeader"));
        
        if (hasRequestHeader) {
            return "header";
        }
        
        // @RequestParam이 있거나 기본값은 query로 분류
        return "query";
    }

    private boolean isParameterRequired(com.github.javaparser.ast.body.Parameter param) {
        return param.getAnnotations().stream()
            .anyMatch(ann -> ann.getNameAsString().equals("PathVariable")) ||
           param.getAnnotations().stream()
            .anyMatch(ann -> ann.getNameAsString().equals("RequestBody"));
    }

    /**
     * 파라미터의 검증 어노테이션 추출
     */
    private String[] extractValidationAnnotations(com.github.javaparser.ast.body.Parameter param) {
        return param.getAnnotations().stream()
            .map(AnnotationExpr::getNameAsString)
            .filter(name -> name.equals("Valid") || name.equals("NotNull") || 
                          name.equals("Size") || name.equals("NotBlank") ||
                          name.equals("RequestBody") || name.equals("PathVariable") ||
                          name.equals("RequestParam") || name.equals("RequestHeader"))
            .toArray(String[]::new);
    }

    /**
     * 메서드가 던질 수 있는 예외 목록 추출합
     */
    private List<String> extractExceptions(MethodDeclaration n) {
        return n.getThrownExceptions().stream()
            .map(exception -> exception.toString())
            .collect(Collectors.toList());
    }

    /**
     * 컨트롤러에서 사용되는 DTO 클래스들 감지
     * @RequestBody 파라미터와 반환 타입에서 DTO 클래스 추출
     * 단, 이미 처리된 DTO 파일은 제외
     */
    private void detectRelatedDtos(ClassOrInterfaceDeclaration controllerClass) {
        // 모든 메서드에서 DTO 클래스 감지
        controllerClass.getMethods().stream()
            .filter(this::isMappingMethod)
            .forEach(method -> {
                // @RequestBody 파라미터에서 DTO 감지
                detectDtoFromParameters(method);
                
                // 반환 타입에서 DTO 감지
                detectDtoFromReturnType(method);
            });
    }

    /**
     * 메서드 파라미터에서 DTO 클래스 감지
     */
    private void detectDtoFromParameters(MethodDeclaration method) {
        method.getParameters().stream()
            .filter(param -> param.getAnnotations().stream()
                .anyMatch(ann -> ann.getNameAsString().equals("RequestBody")))
            .forEach(param -> {
                String typeName = param.getType().toString();
                String className = TypeParser.parseType(typeName, TypeParser.ParseMode.CLASS_NAME_ONLY).getBaseType();
                if (isDtoClassName(className)) {
                    addDtoIfNotExists(className, method);
                }
            });
    }

    /**
     * 메서드 반환 타입에서 DTO 클래스 감지
     */
    private void detectDtoFromReturnType(MethodDeclaration method) {
        String returnType = method.getType().toString();
        
        // 직접 반환 타입 확인
        String className = TypeParser.parseType(returnType, TypeParser.ParseMode.CLASS_NAME_ONLY).getBaseType();
        if (isDtoClassName(className)) {
            addDtoIfNotExists(className, method);
        }
        
        extractDtosFromGenericType(returnType, method);
    }

    /**
     * 제네릭 타입에서 DTO 클래스들을 재귀적으로 추출
     * 예: List<UserDto>, ResponseEntity<UserDto>, Optional<List<UserDto>> 등
     */
    private void extractDtosFromGenericType(String typeName, MethodDeclaration method) {
        TypeParseResult result = TypeParser.parseType(typeName, TypeParser.ParseMode.DTO_EXTRACTION);
        
        for (String genericType : result.getGenericTypes()) {
            String className = TypeParser.parseType(genericType, TypeParser.ParseMode.CLASS_NAME_ONLY).getBaseType();
            if (isDtoClassName(className)) {
                addDtoIfNotExists(className, method);
            }
            
            // 중첩된 제네릭 타입도 재귀적으로 처리
            extractDtosFromGenericType(genericType, method);
        }
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
     * DTO 클래스가 이미 존재하지 않으면 추가하고 재귀적으로 처리
     */
    private void addDtoIfNotExists(String className, MethodDeclaration method) {
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
                    System.err.println("DTO 파일 처리 중 오류: " + filePath + " - " + e.getMessage());
                    // 오류 발생 시 빈 DTO 정보만 추가
                    addEmptyDto(className, filePath);
                }
            } else {
                // 파일을 찾지 못한 경우 빈 DTO 정보만 추가
                String estimatedPath = "src/main/java/com/example/dto/" + className + ".java";
                addEmptyDto(className, estimatedPath);
            }
        }
    }
    
    /**
     * 빈 DTO 정보 추가 (파일을 찾지 못한 경우)
     */
    private void addEmptyDto(String className, String filePath) {
        DtoInfo dto = DtoInfo.builder()
            .className(className)
            .fields(new ArrayList<>())
            .existingAnnotations(new HashMap<>())
            .filePath(filePath)
            .build();
        dtoClasses.add(dto);
    }

    /**
     * 클래스명으로 파일 검색 (패키지명 없이)
     * 더 광범위한 검색을 수행
     */
    private String findDtoFileByClassName(String className) {
        try {
            // 프로젝트 전체에서 해당 클래스명의 파일 검색
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
