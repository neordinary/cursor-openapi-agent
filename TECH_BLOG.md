# "이 필드 필수인가요?" 매일 답변하는 당신에게: Cursor로 Swagger 문서 자동화하기

Spring Boot 프로젝트에서 Swagger는 이미 대부분의 팀이 사용하고 있습니다. 하지만 문제는 **인력 부족**으로 인해 문서 품질이 떨어진다는 것입니다. 설명이 불완전하고, 팀원마다 작성 스타일이 다르며, API가 변경되어도 문서 업데이트가 늦어집니다. 이번 글에서는 **JavaParser**와 **Cursor IDE의 LLM**을 결합하여 팀에 맞는 일관된 Swagger 문서를 자동으로 생성하는 시스템을 구축한 경험을 공유합니다.

## Swagger 문서의 진짜 문제는 무엇인가?
<img width="1248" height="832" alt="Gemini_Generated_Image_8ee8e28ee8e28ee8" src="https://github.com/user-attachments/assets/564696db-90ad-4092-9d69-1290822cfd75" />


### 1. 불완전한 문서 설명

Swagger를 도입했지만, 실제로는 최소한의 주석만 작성하는 경우가 많습니다. 예를 들어,

```java
@GetMapping("/{tripId}")
public ResponseEntity<TripResponse> getTripById(@PathVariable Long tripId) {
    // 비즈니스 로직
}
```

Swagger UI에는 표시되지만 설명이 거의 없습니다. 제대로 문서화하려면 다음처럼 작성해야 합니다.

```java
@Operation(
    summary = "여행 상세 조회",
    description = "여행 ID로 특정 여행의 상세 정보를 조회합니다. 존재하지 않는 여행 ID일 경우 404를 반환합니다.",
    tags = {"여행"}
)
@ApiResponses(value = {
    @ApiResponse(responseCode = "200", description = "여행 조회 성공"),
    @ApiResponse(responseCode = "404", description = "여행을 찾을 수 없음"),
    @ApiResponse(responseCode = "500", description = "서버 오류")
})
@Parameter(name = "tripId", in = ParameterIn.PATH, required = true, description = "여행 식별자")
@GetMapping("/{tripId}")
public ResponseEntity<TripResponse> getTripById(@PathVariable Long tripId) {
    // 비즈니스 로직
}
```

하지만 인력이 부족한 상황에서 50개, 100개의 엔드포인트에 이런 수준의 주석을 일일이 작성하고 유지하기란 현실적으로 어렵습니다. 결국 문서는 불완전한 상태로 방치되죠. 

### 2. 일관성 유지의 어려움

수동으로 API 주석을 작성하다 보면 일관성을 유지하기가 쉽지 않습니다. 개발 일정에 쫓기다 보면 200 성공 응답만 작성하고 404나 400 같은 에러 응답은 빠뜨리기 일쑤입니다. 팀원마다 주석 작성 스타일도 다르죠. 누군가는 상세하게, 누군가는 간략하게 작성합니다. 특히 DTO 필드 설명이나 boolean 타입 필드의 의미가 불명확하면 프론트엔드 개발자가 혼란을 겪게 됩니다.

### 3. 느린 문서 업데이트

API 스펙이 변경되었는데 문서가 업데이트되지 않으면 통합 테스트 단계에서야 문제를 발견하게 됩니다. "이 필드는 필수인가요?", "어떤 경우에 404가 나오나요?" 같은 질문이 슬랙으로 계속 날아오죠. 인력이 부족할수록 문서 업데이트는 우선순위에서 밀려납니다.


## 해결책: 팀에 맞는 자동화

이 프로젝트의 핵심 가치는 **Cursor의 `.md` 파일만 수정하면 팀에 맞는 일관된 형식으로 Swagger 주석을 자동 생성할 수 있다**는 것입니다.

- **일관된 형식**: 팀 전체가 동일한 스타일로 주석 작성
- **설명의 깊이 조절**: 간단한 설명부터 상세한 설명까지 자유롭게 설정
- **빠른 업데이트**: 코드 변경 시 명령어 하나로 문서 갱신
- **팀별 커스터마이징**: `.cursor/commands/swg-apply.md` 파일에서 규칙 수정 가능

Swagger는 이미 쓰고 있습니다. 이제 문서 **품질**을 높일 차례입니다.

## 실습 환경

이 도구는 다음 환경에서 작동합니다.

- **프로젝트**: Spring Boot (Java 17+)
- **빌드 도구**: Gradle
- **IDE**: Cursor IDE
- **분석 도구**: JavaParser (AST 분석)
- **주석 생성**: LLM (Cursor)

**예시 프로젝트 구조**

```
your-spring-project/
├── src/main/java/
│   └── com/example/
│       ├── controller/
│       │   ├── TripController.java
│       │   └── UserController.java
│       └── dto/
│           ├── TripCreateRequest.java
│           └── TripResponse.java
└── cursor-openapi-agent/      # 설치된 Agent
    ├── extractor/             # JavaParser 기반 추출기
    ├── out/                   # endpoints.json 출력
    └── scripts/               # 실행 스크립트
```


## Step 1. JavaParser로 코드 메타데이터 추출하기

### 왜 JavaParser인가?

Spring Boot 컨트롤러에서 다음 정보를 추출해야 합니다.

- HTTP 메소드 (GET, POST, PUT, DELETE)
- 엔드포인트 경로
- 파라미터 (PathVariable, RequestParam, RequestBody)
- 반환 타입
- DTO 필드 정보

이를 정규표현식으로 파싱하면 복잡하고 오류가 발생하기 쉽습니다. **JavaParser**는 Java 코드를 AST(Abstract Syntax Tree)로 파싱하여 정확한 정보를 추출할 수 있습니다.

### 추출기 구조

추출기는 3개의 핵심 클래스로 구성됩니다.

```
extractor/
├── Main.java                  # 명령행 진입점
├── ControllerExtractor.java   # 파일 탐색 및 조정
├── ControllerVisitor.java     # 컨트롤러 AST 방문
└── DtoVisitor.java            # DTO AST 방문
```

### 컨트롤러 정보 추출

`ControllerVisitor`는 AST를 순회하며 다음을 수행합니다.

1. **컨트롤러 클래스 감지**

```java
@RestController
@RequestMapping("/api/trips")
public class TripController {
    // ...
}
```

`@RestController` 어노테이션이 있는 클래스를 찾아 `requestMapping` 경로를 추출합니다.

2. **HTTP 메소드 정보 추출**

```java
@GetMapping("/{tripId}")
public ResponseEntity<TripResponse> getTripById(@PathVariable Long tripId) {
    // ...
}
```

- HTTP 메소드: `GET` (`@GetMapping`에서 추출)
- 경로: `/{tripId}`
- 파라미터: `tripId` (타입: `Long`, 위치: `PATH`, 필수: `true`)
- 반환 타입: `ResponseEntity<TripResponse>`

3. **파라미터 위치 판별**

```java
private String determineParameterIn(Parameter param) {
    if (param.getAnnotations().stream()
        .anyMatch(ann -> ann.getNameAsString().equals("RequestBody"))) {
        return "body";
    }
    if (param.getAnnotations().stream()
        .anyMatch(ann -> ann.getNameAsString().equals("PathVariable"))) {
        return "path";
    }
    // RequestParam, RequestHeader 등도 판별
    return "query";
}
```

### DTO 정보 추출

`DtoVisitor`는 DTO 클래스의 필드 정보를 추출합니다.

```java
public class TripCreateRequest {
    @NotBlank(message = "여행 제목은 필수입니다")
    @Size(max = 100, message = "여행 제목은 100자 이하여야 합니다")
    private String title;
    
    @NotNull(message = "시작일은 필수입니다")
    private LocalDate startDate;
    
    private String destination;
}
```

추출되는 정보
- 필드명: `title`, `startDate`, `destination`
- 타입: `String`, `LocalDate`, `String`
- 검증 어노테이션: `[@NotBlank, @Size]`, `[@NotNull]`, `[]`
- 필수 여부: `true`, `true`, `false`

### 연관 DTO 자동 감지

컨트롤러에서 사용되는 DTO를 자동으로 감지합니다:

```java
@PostMapping
public ResponseEntity<TripResponse> createTrip(@RequestBody TripCreateRequest request) {
    // ...
}
```

- `@RequestBody` 파라미터의 `TripCreateRequest` 감지
- 반환 타입의 `TripResponse` 감지 (제네릭 타입 재귀 파싱)
- 해당 DTO 파일을 찾아 자동으로 분석

### 출력: endpoints.json

추출 결과는 다음과 같은 JSON 형식으로 저장됩니다:

```json
{
  "controllers": [
    {
      "className": "TripController",
      "requestMapping": "/api/trips",
      "methods": [
        {
          "methodName": "getTripById",
          "httpMethod": "GET",
          "path": "/{tripId}",
          "returnType": "ResponseEntity<TripResponse>",
          "parameters": [
            {
              "name": "tripId",
              "type": "Long",
              "in": "path",
              "required": true
            }
          ]
        }
      ]
    }
  ],
  "dtoClasses": [
    {
      "className": "TripResponse",
      "filePath": "src/main/java/com/example/dto/TripResponse.java",
      "fields": [
        {
          "name": "id",
          "type": "Long",
          "required": true
        },
        {
          "name": "title",
          "type": "String",
          "validationAnnotations": ["NotBlank", "Size"],
          "required": true
        }
      ]
    }
  ]
}
```

## Step 2. Cursor 명령어로 실행하기

### 명령어 설치

Cursor IDE에는 `.cursor/commands/` 디렉터리에 커스텀 명령어를 추가할 수 있습니다.

프로젝트 루트에서 자동 설치

```bash
curl -sSL https://raw.githubusercontent.com/qlqlrh/cursor-openapi-agent/main/install.sh | bash
```

설치 후 다음 명령어를 사용할 수 있습니다.

### `/swg-extract`: 메타데이터 추출

**전체 스캔 모드**

```
/swg-extract
```

`src/main/java` 폴더의 모든 컨트롤러와 DTO를 스캔합니다.

**선택적 스캔 모드**

```
/swg-extract @UserController.java
/swg-extract @TripController.java @TripCreateRequest.java
```

특정 파일만 스캔할 수 있습니다. 컨트롤러를 지정하면 연관된 DTO도 자동으로 감지됩니다.

실행 흐름

```
1. run_extract.sh 스크립트 실행
2. extractor.jar가 없으면 Gradle 빌드
3. JavaParser로 코드 분석
4. endpoints.json 생성
5. 추출 통계 출력
```

출력 예시

```
? 컨트롤러 메타데이터 추출 중 (전체 스캔)...
소스: /Users/project/src/main/java
출력: /Users/project/cursor-openapi-agent/out/endpoints.json
? 메타데이터 추출 완료!
? 결과: 컨트롤러 3개, API 메소드 15개
? DTO 클래스: 8개
? 저장됨: /Users/project/cursor-openapi-agent/out/endpoints.json
```

## Step 3. LLM이 Swagger 주석 생성하기

### `/swg-apply`: 주석 적용

메타데이터 추출이 완료되면 다음 명령어를 실행합니다.

```
/swg-apply
```

이 명령어는 Cursor IDE의 LLM에게 다음 작업을 지시합니다.

1. `endpoints.json` 파일 읽기
2. 각 컨트롤러 메소드에 `@Operation`, `@ApiResponse`, `@Parameter` 주석 생성
3. 각 DTO 필드에 `@Schema` 주석 생성
4. 파일 수정 제안을 IDE에 표시

### 팀에 맞게 커스터마이징하기

`.cursor/commands/swg-apply.md` 파일에는 LLM이 따라야 할 규칙이 정의되어 있습니다. **이 파일을 수정하면 팀의 문서화 스타일을 자유롭게 조절할 수 있습니다.** 아래는 예시입니다.

```markdown
## 규칙

### 컨트롤러 주석 규칙
1. **한국어 summary/description**: 간결하고 모호하지 않게 작성
2. **tag**: 컨트롤러명 기반 (예: "여행", "여정", "사용자")
3. **표준 응답**: presets를 따르되, 메소드/검증/예외로 합리화
4. **파라미터**: Path/Query/Header/Body에 맞는 @Parameter 생성
5. **기존 주석**: merge 규칙으로 갱신

### DTO 주석 규칙
1. **@Schema**: 클래스 레벨에 한국어 description 추가
2. **필드 설명**: 각 필드에 적절한 한국어 설명 추가
3. **검증 정보**: validationAnnotations 기반으로 required, example 등 설정

## 응답 코드 정책
- **200**: 성공 (GET, PUT)
- **201**: 생성 성공 (POST)
- **204**: 삭제 성공 (DELETE)
- **400**: 잘못된 요청 (검증 실패)
- **404**: 리소스 없음
- **500**: 서버 오류
```

**팀별 커스터마이징**
- 영어 주석을 선호한다면? → "한국어 summary" → "English summary"로 변경
- 간결한 설명을 원한다면? → "간결하고 모호하지 않게" → "한 줄 요약만"으로 변경
- 특정 응답 코드를 추가하려면? → 응답 코드 정책에 팀 규칙 추가

`.md` 파일만 수정하면 전체 프로젝트에 일관되게 적용됩니다.

## Step 4. 생성된 주석 확인 및 적용

### 컨트롤러 주석 예시

**Before**

```java
@GetMapping("/{tripId}")
public ResponseEntity<TripResponse> getTripById(@PathVariable Long tripId) {
    return ResponseEntity.ok(tripService.findById(tripId));
}
```

**After**

```java
@Operation(
    summary = "여행 상세 조회",
    description = "여행 ID로 특정 여행의 상세 정보를 조회합니다. 존재하지 않는 여행 ID일 경우 404를 반환합니다.",
    tags = {"여행"}
)
@ApiResponses(value = {
    @ApiResponse(responseCode = "200", description = "여행 조회 성공"),
    @ApiResponse(responseCode = "404", description = "여행을 찾을 수 없음"),
    @ApiResponse(responseCode = "500", description = "서버 오류")
})
@GetMapping("/{tripId}")
public ResponseEntity<TripResponse> getTripById(
    @Parameter(name = "tripId", in = ParameterIn.PATH, required = true, description = "여행 식별자")
    @PathVariable Long tripId
) {
    return ResponseEntity.ok(tripService.findById(tripId));
}
```

### DTO 주석 예시

**Before**

```java
public class TripCreateRequest {
    @NotBlank(message = "여행 제목은 필수입니다")
    @Size(max = 100)
    private String title;
    
    @NotNull
    private LocalDate startDate;
    
    private String destination;
}
```

**After**

```java
public class TripCreateRequest {
    @Schema(description = "여행 제목", example = "제주도 3박 4일 여행", required = true, maxLength = 100)
    @NotBlank(message = "여행 제목은 필수입니다")
    @Size(max = 100)
    private String title;
    
    @Schema(description = "여행 시작일", example = "2024-03-15", required = true)
    @NotNull
    private LocalDate startDate;
    
    @Schema(description = "여행 목적지", example = "제주도")
    private String destination;
}
```

### Accept/Reject 선택

Cursor IDE에서 변경사항을 검토하고 Accept 또는 Reject를 선택할 수 있습니다.
<img width="868" height="205" alt="스크린샷 2025-11-13 오후 2 55 38" src="https://github.com/user-attachments/assets/f0245f7e-c917-4b22-95f1-b901dc8ef17e" />


## 업무에 미친 영향
<img width="1472" height="704" alt="Gemini_Generated_Image_62ula762ula762ul" src="https://github.com/user-attachments/assets/80a4d115-b66d-421a-a3c5-6e82e73d023b" />


### 문서 품질의 극적인 개선

이전에는 Swagger가 도입되어 있어도 설명이 불완전하거나 누락된 상태였습니다. 어떤 엔드포인트는 상세한 설명이 있는 반면, 다른 엔드포인트는 기본 정보만 있는 식이었죠. 이제는 모든 엔드포인트가 일관된 형식과 완전한 설명을 갖추게 되었습니다. 특히 신입 개발자도 명령어 하나로 팀 규칙에 맞는 문서를 생성할 수 있게 되면서, 문서 작성 경험이 부족해도 고품질 문서를 만들 수 있게 되었습니다.

### 빠른 문서 업데이트

이전에는 API를 수정한 후 문서 업데이트를 깜빡하거나 미루는 경우가 빈번했습니다. "나중에 정리하자"고 생각하면 결국 하지 않게 되죠. 이제는 `/swg-extract` → `/swg-apply` 명령어 두 개로 1~2분 내에 문서를 갱신할 수 있습니다. 코드를 수정하고 바로 문서를 업데이트하는 것이 부담 없어지면서, 코드와 문서 간 불일치 문제가 거의 사라졌습니다.

### 협업 효율성 증가

**프론트엔드 팀의 피드백**
- "API 스펙이 명확해서 개발 중 질문이 줄었어요"
- "필수 파라미터와 선택적 파라미터가 문서에 명시되어 있어 좋아요"
- "예시값이 있어서 테스트 데이터를 만들기 편해요"

**백엔드 팀의 피드백**
- "팀 전체가 일관된 형식으로 문서를 작성하게 되었어요"
- "신입 개발자도 명령어만 알면 바로 적용할 수 있어요"
- "코드 리뷰에서 '문서 업데이트해주세요' 코멘트가 사라졌어요"

### 팀 문화 개선

이전에는 Swagger 문서가 있어도 "어차피 불완전할 거야"라는 인식이 있었습니다. 이제는 자동화로 일관된 품질이 보장되면서 문서에 대한 신뢰도가 높아졌고, 팀 전체가 문서 우선 개발 문화를 갖추게 되었습니다.

## 한계점과 앞으로의 도전

### 현재의 한계점

이 도구가 많은 문제를 해결하지만, 여전히 몇 가지 한계가 있습니다.

**1. Spring Boot 생태계에 국한**

현재는 Spring Boot + Java 환경만 지원합니다. NestJS, FastAPI, Django 같은 다른 프레임워크를 사용하는 팀은 이 도구를 바로 활용할 수 없습니다. Kotlin으로 작성된 Spring Boot 프로젝트도 마찬가지입니다. JavaParser를 사용하기 때문에 Java 코드만 분석할 수 있다는 제약이 있습니다.

**2. Cursor IDE 의존성**

현재는 Cursor IDE의 LLM과 커스텀 명령어 기능에 의존하고 있습니다. VSCode, IntelliJ 같은 다른 IDE를 사용하는 팀은 이 도구를 편리하게 활용할 수 없습니다. IDE 선택의 자유가 없다는 것은 도구의 확산에 큰 제약이 됩니다.

**3. 특정 LLM에 종속**

Cursor IDE에 내장된 LLM만 사용할 수 있기 때문에, 다른 LLM(GPT-4, Claude, Gemini 등)을 선택할 수 없습니다. 팀마다 선호하는 LLM이나 자체 구축한 LLM이 있을 수 있는데, 현재는 그런 선택권이 없습니다.

**4. 복잡한 비즈니스 로직 이해의 한계**

도구는 코드 구조를 분석할 수 있지만, 비즈니스 맥락까지 이해하지는 못합니다. "결제 승인 후 24시간 이내 환불 가능"같은 비즈니스 규칙은 주석에 자동으로 반영되지 않습니다. 이런 부분은 여전히 개발자가 직접 추가해야 합니다.

### 앞으로의 도전

이런 한계들을 극복하기 위해 다음과 같은 방향을 고민하고 있습니다.

**1. 다른 언어와 프레임워크 지원**

Python에서 가장 많이 사용되는 FastAPI와 Django, JavaScript/TypeScript에서 가장 많이 사용되는 Express.js, NestJS 프레임워크를 지원하는 것이 다음 목표입니다. 이 프레임워크들을 지원하면 Spring Boot 외의 환경에서도 동일한 방식으로 API 문서 생성을 자동화할 수 있습니다.

**2. 다양한 IDE와 LLM 지원**

VSCode, IntelliJ 등 주요 IDE에 Extension을 개발하는 것이 다음 목표입니다. Extension 내에서 여러 LLM API(ChatGPT, Claude, Gemini 등)를 선택할 수 있도록 만들면, 사용자가 선호하는 IDE와 LLM을 자유롭게 조합해서 사용할 수 있습니다. Cursor IDE가 아닌 환경에서도 동일한 자동화 경험을 제공할 수 있습니다.

**3. 프로젝트 컨텍스트 학습**

RAG(Retrieval-Augmented Generation) 기술을 활용하면 더 정확한 주석 생성이 가능합니다. 프로젝트의 기획 문서나 정책서를 학습해서 프로젝트 특유의 용어나 비즈니스 규칙을 이해할 수 있다면, 단순히 코드 구조를 분석하는 것을 넘어 진짜 맥락에 맞는 설명을 생성할 수 있습니다. 예를 들어 "결제 승인 후 24시간 이내 환불 가능" 같은 비즈니스 규칙도 자동으로 주석에 반영될 수 있습니다.

## 마치며

"이 필드 필수인가요?" 매일 이 질문에 답변하느라 지치셨나요? Swagger 문서가 있어도 계속 API 스펙에 대한 질문이 생기는 이유는 간단합니다. **문서가 불완전하기 때문**입니다.

이 도구는 Swagger를 도입하는 것이 아니라, 이미 사용 중인 Swagger의 품질을 끌어올립니다. `.cursor/commands/swg-apply.md` 파일 하나만 수정하면 팀 전체가 일관된 형식으로 문서를 작성하게 됩니다. 더 이상 "문서 업데이트해주세요"라는 코드 리뷰 코멘트를 받지 않아도 되고, 질문도 줄어듭니다.

Swagger는 이미 쓰고 있습니다. 이제는 품질을 높일 차례입니다.

코드는 [GitHub 저장소](https://github.com/neordinary/cursor-openapi-agent)에서 확인할 수 있습니다. 직접 사용해보세요!

## 참고 자료

- [JavaParser 공식 문서](https://javaparser.org/)
- [OpenAPI/Swagger 명세](https://swagger.io/specification/)
- [Cursor IDE 문서](https://docs.cursor.com/)
