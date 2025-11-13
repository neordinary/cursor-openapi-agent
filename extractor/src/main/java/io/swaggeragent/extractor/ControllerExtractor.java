package io.swaggeragent.extractor;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import io.swaggeragent.extractor.model.*;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Spring Boot Controller에서 API 엔드포인트 정보를 추출
 * 
 * JavaParser를 사용하여 Java 소스 코드를 AST(Abstract Syntax Tree)로 파싱하고,
 * Spring Boot의 @RestController, @Controller 어노테이션이 있는 클래스에서
 * API 엔드포인트 정보를 추출
 * 
 * @author qlqlrh
 * @version 1.0
 */
@RequiredArgsConstructor
public class ControllerExtractor {
    // Java 소스 코드 파싱을 위한 JavaParser
    private final JavaParser javaParser;
    
    // 추출된 Controller 정보 저장
    private final List<ControllerInfo> controllers;
    
    // 추출된 DTO 정보를 저장
    private final Set<DtoInfo> dtoClasses;
    
    // 프로젝트 루트 경로 (파일 검색용)
    private String projectRoot = System.getProperty("user.dir");

    /**
     * 지정된 소스 경로에서 Controller 정보를 추출
     */
    public EndpointsInfo extract(String sourcePath) throws IOException {
        Path sourceDir = Paths.get(sourcePath);
        
        // 디렉토리를 재귀적으로 탐색
        Files.walk(sourceDir)
            .filter(path -> path.toString().endsWith(".java"))  // .java 파일만 필터링
            .filter(path -> path.toString().contains("controller"))  // 'controller'가 포함된 파일만 필터링
            .forEach(this::processFile);  // 각 파일을 처리
        
        return EndpointsInfo.ofControllersAndDtos(controllers, new ArrayList<>(dtoClasses));
    }

    /**
     * 선택된 파일들에서 Controller와 DTO 정보를 통합 추출
     * - 컨트롤러 파일: API 엔드포인트 정보 추출
     * - DTO 파일: 데이터 모델 정보 추출
     * - 혼합 파일: 컨트롤러와 DTO 모두 처리
     */
    public EndpointsInfo extractFromFiles(List<String> filePaths) throws IOException {
        int processedFiles = 0;
        
        for (String filePath : filePaths) {
            Path path = Paths.get(filePath);
            if (Files.exists(path) && path.toString().endsWith(".java")) {
                FileProcessResult result = processFileWithResult(path);
                if (result.isSuccess()) {
                    processedFiles++;
                }
            } else {
                System.err.println("파일을 찾을 수 없거나 Java 파일이 아닙니다: " + filePath);
            }
        }
        
        // 처리된 파일이 없으면 빈 결과 반환
        if (processedFiles == 0) {
            return EndpointsInfo.ofControllersAndDtos(new ArrayList<>(), new ArrayList<>());
        }
        
        return EndpointsInfo.ofControllersAndDtos(controllers, new ArrayList<>(dtoClasses));
    }


    /**
     * JavaParser를 사용하여 파일을 파싱하고, ControllerVisitor를 통해
     * Controller 클래스와 메서드 정보를 추출
     */
    private void processFile(Path filePath) {
        try {
            // 파일을 AST로 파싱
            CompilationUnit cu = javaParser.parse(filePath).getResult().orElse(null);
            if (cu == null) return;

            // 파일명으로 DTO 클래스인지 확인
            if (isDtoFile(filePath)) {
                cu.accept(new DtoVisitor(filePath, dtoClasses, projectRoot, javaParser), null);
            } else {
                // AST를 순회하며 Controller 정보 추출
                cu.accept(new ControllerVisitor(controllers, dtoClasses, projectRoot, javaParser), null);
            }
        } catch (Exception e) {
            System.err.println("Error processing file: " + filePath + " - " + e.getMessage());
        }
    }

    /**
     * 파일 처리 결과를 반환하는 개선된 메서드
     */
    private FileProcessResult processFileWithResult(Path filePath) {
        try {
            // 파일을 AST로 파싱
            CompilationUnit cu = javaParser.parse(filePath).getResult().orElse(null);
            if (cu == null) {
                return FileProcessResult.error("파일을 파싱할 수 없습니다: " + filePath);
            }

            boolean isDto = isDtoFile(filePath);
            boolean isController = false;
            
            // 파일명으로 DTO 클래스인지 확인
            if (isDto) {
                cu.accept(new DtoVisitor(filePath, dtoClasses, projectRoot, javaParser), null);
            } else {
                // AST를 순회하며 Controller 정보 추출
                cu.accept(new ControllerVisitor(controllers, dtoClasses, projectRoot, javaParser), null);
                isController = true; // 컨트롤러 파일로 처리됨
            }
            
            return FileProcessResult.success(isController, isDto);
            
        } catch (Exception e) {
            return FileProcessResult.error("파일 처리 중 오류 발생: " + filePath + " - " + e.getMessage());
        }
    }

    /**
     * 파일명 패턴으로 DTO 클래스인지 확인
     * 패턴: *Dto, *DTO, *Req, *Res,*Request, *Response
     */
    private boolean isDtoFile(Path filePath) {
        String fileName = filePath.getFileName().toString();
        String className = fileName.substring(0, fileName.lastIndexOf('.'));
        
        return className.endsWith("Dto") || 
               className.endsWith("DTO") || 
               className.endsWith("Req") || 
               className.endsWith("Res") || 
               className.endsWith("Request") || 
               className.endsWith("Response");
    }
}