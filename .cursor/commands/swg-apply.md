# Cursor 전용 자동 스웨거 주석 생성 에이전트

## 역할
추출된 메타데이터로 Swagger 주석을 생성/갱신하는 코드 변환 에이전트입니다.

## 입력
- `cursor-openapi-agent/out/endpoints.json`: 컨트롤러 및 DTO 메타데이터

## 출력
- 컨트롤러 파일에 Swagger 주석 추가 (`@Operation`, `@ApiResponse` 등)
- DTO 파일에 Swagger 주석 추가 (`@Schema` 등)
- IDE에서 Accept/Reject 선택 가능

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
- **401**: 인증 실패
- **403**: 권한 없음
- **404**: 리소스 없음
- **409**: 충돌 (중복 생성 등)
- **500**: 서버 오류

## 메소드별 자동 응답
- POST → 201 (생성)
- DELETE → 204 (삭제)
- PUT/PATCH → 200 (수정)
- GET → 200 (조회)

## 데이터 흐름
```
[Java Source] --(Extractor)--> endpoints.json --(Cursor LLM)--> [Java Source Patched]
```

## 실행 순서
1. `/swg-extract` → `endpoints.json` 생성 (컨트롤러 + DTO)
2. `/swg-apply` → Cursor LLM이 컨트롤러/DTO 파일 수정
3. IDE에서 Accept/Reject 선택

## 주의사항
- 기존 주석이 있으면 마커(`// @generated-by: swagger-agent`) 기준으로만 덮어쓰기
- 수동으로 작성된 주석은 보존
- summary는 40자 내외로 간결하게
- description에는 검증 실패/권한/에러 언급 1~2문장 포함

## 현재 작업
`endpoints.json` 파일을 읽고 **추출된 모든 정보에 대해** Swagger 주석을 적용하세요.

**동작 방식:**
- `endpoints.json`에서 컨트롤러 및 DTO 메타데이터 읽기
- **컨트롤러가 있는 경우**: 각 컨트롤러 파일에 `@Operation`, `@ApiResponse` 등 주석 추가
- **DTO가 있는 경우**: 각 DTO 파일에 `@Schema` 주석 추가
- **둘 다 없는 경우**: "처리할 파일이 없습니다" 메시지 출력
- 한국어 summary/description, 표준 응답 코드, 파라미터 설명 자동 생성
- **필수: 수정된 각 파일에 대한 직접 링크 제공**: 사용자가 수정된 파일에 바로 접근할 수 있도록 반드시 `file://절대경로` 형식의 클릭 가능한 링크를 제공해야 합니다. 절대 빼먹지 마세요!

**처리 우선순위:**
1. 컨트롤러와 DTO가 모두 있으면 → 둘 다 처리
2. 컨트롤러만 있으면 → 컨트롤러만 처리
3. DTO만 있으면 → DTO만 처리
4. 둘 다 없으면 → 아무것도 하지 않음 (억지로 뭔가 하려고 하지 마)

## 📋 수정된 파일 리스트 (필수)

**반드시 제공해야 할 파일 링크:**

추출된 정보에 따라 수정된 파일들을 동적으로 감지하여 출력하세요. **수정하거나 찾은 모든 파일에 대해 반드시 클릭 가능한 링크를 제공해야 합니다.**

1. `endpoints.json` 파일을 읽어서 컨트롤러 및 DTO 개수 확인
2. **둘 다 0개인 경우: "처리할 파일이 없습니다" 메시지 출력**
3. **컨트롤러가 있는 경우: 각 컨트롤러 파일의 수정 내용을 분석하여 요약하고 반드시 파일 링크 제공**
4. **DTO가 있는 경우: 각 DTO 파일의 수정 내용을 분석하여 요약하고 반드시 파일 링크 제공**
5. 추가된 Swagger 주석의 종류와 개수를 카운트
6. 한국어 summary/description과 태그 분류 정보 출력
7. **필수: 각 수정된 파일에 대한 직접 링크 제공** - `file://절대경로` 형식으로 반드시 제공. 링크 없이 파일명만 나열하는 것은 허용되지 않습니다.

**출력 형식:**
```
## 📋 수정된 파일 리스트

[처리할 파일이 없는 경우]
처리할 파일이 없습니다.

[컨트롤러가 있는 경우]
다음 컨트롤러 파일들에 대해서 Swagger 주석이 생성되었습니다:
- [UserController.java](file:///Users/dia/repositories/cursor-openapi-agent/src/main/java/.../UserController.java) - 수정 내용 요약
- [TravelController.java](file:///Users/dia/repositories/cursor-openapi-agent/src/main/java/.../TravelController.java) - 수정 내용 요약

[DTO가 있는 경우]
다음 DTO 파일들이 Swagger 주석이 생성되었습니다:
- [UserDto.java](file:///Users/dia/repositories/cursor-openapi-agent/src/main/java/.../UserDto.java) - 수정 내용 요약
- [TravelRequest.java](file:///Users/dia/repositories/cursor-openapi-agent/src/main/java/.../TravelRequest.java) - 수정 내용 요약

## 🎯 공통 추가 사항

[실제로 추가된 공통 사항들을 동적으로 분석하여 출력]

## 📊 통계

[실제 수정 내용을 기반으로 한 동적 통계 출력]
```

**중요**: 파일을 수정하거나 찾았으면 반드시 해당 파일에 대한 클릭 가능한 링크를 제공하세요. 링크 없이 파일명만 나열하는 것은 허용되지 않습니다.