# UC-014 — Sale Orchestrator riêng cho mỗi Plan

## 1. Phân tích và phạm vi

Đối chiếu đặc tả `writing-block (1).md` với code trên nhánh hiện tại. Không tìm thấy `AGENTS.md` trong workspace đã kiểm tra.

Yêu cầu bổ sung của người dùng: cấu hình Sale Orchestrator **cho mỗi Plan trong Proposition**. Vì vậy association `(tenantId, propositionId, planId)` được cố định ở bản ghi Orchestrator, không phải một lựa chọn có thể đổi trong Draft.

Nhánh backend hiện tại là `SCR-38`, sau merge `1dfed60`. Source hiện tại không còn module Transaction/Invoice, Customer hay Notification; các hợp đồng UC-015 trong Core vẫn tồn tại. UC-014 không khôi phục các module đã bị revert và không viết một luồng lưu Invoice/email thay thế.

Thay đổi có sẵn trước triển khai: dependency Core trong `Ecofood/pom.xml` được người dùng đổi từ `1.0.9` sang `1.1.0`. Thay đổi đó được giữ nguyên. POM Core và version của Core không được sửa.

## 2. Backend cấu hình theo Plan

Mỗi Tenant được phân phối Plan có tối đa một Orchestrator cho Plan đó. Hai Plan trong cùng Proposition có cấu hình, revision, phiên bản Active và audit riêng. Caller chỉ định đúng Orchestrator để tránh chọn nhầm Plan. Theo yêu cầu cập nhật, đã bỏ hoàn toàn danh sách POS phải đăng ký thủ công.

Không sửa `Plan`, `PlanRequest`, trạng thái duyệt, market configuration hay workflow Product/Proposition/Plan hiện có. Cấu hình Sale nằm trong collection `orchestrator`, không ghi lại document Proposition.

### Tái sử dụng thành phần

| Thành phần sẵn có | Cách dùng |
| --- | --- |
| `AuthenticationContext` | Danh tính và vai trò từ token đã xác minh. |
| `PropositionAccess` | Tái sử dụng kiểm tra vai trò, Tenant và quyền đọc Proposition theo scope hiện có. |
| `PropositionRepository` | Xác định đúng Plan trong đúng Proposition, không nhận association khác từ request body. |
| `PlanAvailability` | Kiểm tra duyệt, ngày hiệu lực, xóa và Plan version đã bị thay thế. |
| `SaleOrchestratorConfigProvider` | Provider thật phục vụ hợp đồng UC-015 hiện hữu. Interface và constructor DTO cũ giữ nguyên. |
| `SubmitTransactionRequest`, `InvoiceItemDto`, Jakarta Validator | Giữ toàn bộ ràng buộc DTO hiện hữu khi chuẩn bị payload giao dịch. |
| `PropositionMongoConfiguration` | Dùng lại Mongo conversions trong test mapping, không kết nối MongoDB thật. |
| Global exception handling | Giữ response `{message}`; handler cục bộ bổ sung lỗi JSON/rule không hợp lệ thành HTTP 400. |

### Quyền và association

- Chỉ `TENANT_ADMIN` và `TENANT_PRODUCT_MANAGER` được quản lý cấu hình của Tenant hiện tại.
- Backend kiểm tra Tenant đang được phân phối Plan, Plan thuộc đúng Proposition và Tenant còn trong danh sách distribution partners của Proposition.
- Không có `tenantId`, `propositionId`, `planId` có thể sửa trong DTO cấu hình. Association lấy từ token và đường dẫn resource đã kiểm tra; ObjectId được chuẩn hóa trước khi lưu.
- Distribution partner của giao dịch được lấy từ Tenant đối tác đã được phân phối Plan, đúng convention hiện tại của Proposition.
- Tenant có scope đọc rộng hơn cũng không được ghi cấu hình Sale thay Tenant khác.

### Draft, version và activation

Draft có thể được chuẩn bị trước khi Plan được duyệt/có hiệu lực. Kích hoạt yêu cầu Plan và Proposition approved, effective và schema hợp lệ; không yêu cầu POS ID hay authenticated subject trong cấu hình.

Một Orchestrator chỉ có tối đa một Draft và một Active. Active/Archived không sửa trực tiếp. Tạo version mới sao chép cấu hình Active thành Draft; Active cũ vẫn được runtime đọc. Kích hoạt lưu cả archive phiên bản cũ, phiên bản mới, active pointer và audit bằng một lần save document có `@Version`. Stale revision, concurrent create hoặc save conflict trả 409.

Không thêm Pending Approval/Reject cho Sale configuration. Audit lưu action, số version, actor và thời điểm; các version lưu metadata tạo/sửa/kích hoạt.

### Schema

Hỗ trợ STRING, NUMBER, BOOLEAN, DATE, OBJECT, ARRAY; required, description, JSON default, numeric min/max và EMAIL format. Từ chối script/rule không hỗ trợ, tên trùng, default sai kiểu, range ngược, object/array sai cấu trúc, field nhận dạng do server sở hữu và field payload ngoài schema. Có giới hạn 8 cấp lồng schema, 100 field mỗi object và 1000 phần tử mỗi array payload.

Các field UC-015 bắt buộc giữ đúng kiểu và required:

- Gốc: `invoiceNumber`, `transactionTime`, `currency`, `totalAmount`, `items`.
- Trong mỗi item: `productCode`, `productName`, `quantity`, `unitPrice`, `amount`.

Frontend khóa tên/kiểu/required của các field chuẩn. Backend kiểm tra lại độc lập. Collection schema được copy khi tạo contract để tránh thay đổi cấu trúc vô tình qua tham chiếu.

### API

Base resource:

```text
/api/v1/propositions/{propositionId}/plans/{planId}/sale-orchestrator
```

| Method | Suffix | Chức năng |
| --- | --- | --- |
| GET | rỗng | Cấu hình của Plan cho Tenant hiện tại; 204 nếu Plan hợp lệ nhưng chưa có cấu hình. |
| POST | rỗng | Tạo Draft cho Plan; body `{configuration}`. |
| PUT | `/draft` | Sửa Draft; body `{expectedRevision, configuration}`. |
| GET | `/versions` | Lịch sử Sale versions. |
| POST | `/versions` | Tạo Draft mới; body `{expectedRevision}`. |
| POST | `/validate` | Kiểm tra Draft đã lưu; body `{expectedRevision}`, trả `{valid, errors}`. |
| POST | `/activate` | Kích hoạt Draft đã lưu; body `{expectedRevision}`. |

`configuration` gồm `name`, `description`, `maxItems`, `payloadSchema`, `postWorkflows`. Danh sách workflow chứa các action được bật: `CREATE_OR_LINK_CUSTOMER`, `SEND_EMAIL_NOTIFICATION`; danh sách rỗng tắt tất cả. `posBindings` cũ bị bỏ qua khi đọc JSON và không xuất hiện trong request mới. Document cũ vẫn đọc được, không yêu cầu migration thủ công.

## 3. Hợp đồng tích hợp UC-015

Core bổ sung `SaleOrchestratorRuntime.prepare(tenantId, authenticatedSubject, orchestratorId, payload)`. Đây là Java service contract nội bộ; caller phải kiểm tra `TRANSACTION_SUBMIT` và truyền Tenant/subject lấy từ token đã xác thực.

Provider triển khai:

1. Lấy đúng Orchestrator trong Tenant, không lấy Tenant/Plan từ payload.
2. Chụp một document Active duy nhất; Draft đang sửa không tham gia xử lý.
3. Giữ danh tính caller đã xác thực; không kiểm tra thêm registry POS. Trường `posId` của hợp đồng UC-015 cũ lấy từ authenticated subject để giữ tương thích và phục vụ truy vết.
4. Kiểm tra lại phân phối, approval và hiệu lực Plan/Proposition tại thời điểm runtime.
5. Validate schema, áp dụng default và giữ custom fields trong normalized snapshot.
6. Chỉ map các field chuẩn vào DTO UC-015; kiểm tra Jakarta bean validation, currency và giới hạn items.
7. Trả `PreparedSaleTransaction`: Orchestrator ID/version, binding Tenant/POS/Plan/Proposition, DTO transaction, normalized payload và workflow đã bật.

Provider contract cũ `findForPos(tenant, subject)` vẫn được triển khai. Nếu Tenant có nhiều Plan Active, phương thức cũ fail closed với thông báo cần resolve bằng Orchestrator ID; không chọn tùy tiện Plan đầu tiên. Caller vẫn phải được xác thực và có quyền `TRANSACTION_SUBMIT`; không bỏ Authentication hay Tenant Isolation.

**Phần chưa thể thực thi trên nhánh hiện tại:** chưa có TransactionService/InvoiceRepository, Customer Service hay UC-016 NotificationService để gọi. Vì thế không thêm endpoint giả nhận Sale, không trả thành công giao dịch khi chưa lưu Invoice, không gửi email và không tạo Customer. Workflow được cấu hình/version hóa và chuyển qua contract; chưa có worker thực thi. Chống trùng Invoice, kiểm tra tổng tiền, lưu metadata vào Invoice, chạy workflow sau commit và cập nhật Dashboard phải nối vào UC-015/UC-016 khi những module đó được đưa trở lại. `prepare` không thay thế các kiểm tra nghiệp vụ này.

## 4. Frontend theo từng Plan

Đường dùng: **Propositions → mở Proposition → Plans & market configuration → Plan được phân phối cho Tenant → Sale Orchestrator**.

Mỗi Plan có nút mở/đóng riêng, query key bao gồm Tenant/Proposition/Plan. Form dùng accordion tương tự ảnh tham chiếu, gồm General information, Customer, Sale / Plan, Additional information, Post-workflow và Preview. Có nút Next để chuyển nhóm và Preview chuyển giữa Payload JSON / Schema JSON.

Schema mặc định có hai object tùy chọn `customer` và `additionalInfo` ngoài các field giao dịch UC-015 bắt buộc. Có thể thêm object/array lồng nhau; dữ liệu cấu hình cũ giữ nguyên và được phân nhóm khi hiển thị. Preview tạo ví dụ theo kiểu/default/range và currency của Plan; không gửi giao dịch. Khi lưu, client chỉ gửi các field cấu hình được hỗ trợ, loại `posBindings` của dữ liệu cũ. Nếu input không hợp lệ nằm trong accordion đóng, form mở đúng nhóm trước khi focus thông báo lỗi.

Có Save Draft, Validate, Activate, New Sale version, tab version read-only, bảng history và audit. Thay đổi Draft chưa lưu phải lưu trước khi validate/activate.

Không tự refetch khi cửa sổ nhận focus để tránh reset Draft đang sửa. Có reload rõ ràng khi revision conflict. Activation bị khóa ở UI khi Plan chưa active và vẫn được kiểm tra lại ở backend.

Tái sử dụng API client, React Query, CoreButton, React Icons. CSS riêng, màu phẳng, không gradient/glassmorphism. Chỉ thêm component vào bên trong từng article Plan ở `PropositionDetail.jsx`.

## 5. Database

- Collection `orchestrator`: Tenant/Proposition/Plan cố định, revision, versions, activeVersion, audit và metadata.
- Unique partial index `(tenantId, propositionId, planId)` cho các document có đủ ba field.
- Index `(tenantId, activeVersion)` cho provider lookup.
- Mongo ApplicationRunner tạo index khi ứng dụng khởi động; tài khoản Mongo cần quyền tạo index.
- Không thay collection Proposition/Plan, không ghi Invoice, không seed production, không sửa dữ liệu lịch sử.
- Chưa kiểm tra dữ liệu/index trên một MongoDB thật trong lượt này.

## 6. Danh sách file

Core — mới:

- `src/main/java/com/ecofood/core/dto/SalePayloadSchema.java`
- `src/main/java/com/ecofood/core/dto/PreparedSaleTransaction.java`
- `src/main/java/com/ecofood/core/service/SaleOrchestratorRuntime.java`

Backend — mới dưới `src/main/java/com/ecofood/app/orchestrator/`:

- `PlanSaleOrchestrator.java`, `PlanSaleOrchestratorRepository.java`
- `PlanSaleAccess.java`, `SaleSchemaValidator.java`
- `PlanSaleOrchestratorService.java`, `PlanSaleOrchestratorController.java`
- `PlanSaleErrorHandler.java`, `PlanSaleMongoConfig.java`, `PersistentPlanSaleRuntime.java`

Backend tests — mới dưới `src/test/java/com/ecofood/app/orchestrator/`:

- `SaleFixtures.java`, `SaleSchemaValidatorTest.java`
- `PlanSaleAccessTest.java`, `PlanSaleOrchestratorServiceTest.java`
- `PlanSaleOrchestratorControllerTest.java`, `PlanSaleMongoMappingTest.java`
- `PersistentPlanSaleRuntimeTest.java`

Frontend — mới dưới `src/features/orchestrator/`:

- `api/planSale.api.js`
- `lib/planSale.js`, `lib/planSale.test.js`
- `pages/PlanSaleOrchestrator.jsx`, `pages/PlanSaleEditor.jsx`, `pages/SaleSchemaBuilder.jsx`, `pages/PlanSaleOrchestrator.css`

Frontend — sửa: `src/features/proposition/pages/PropositionDetail.jsx` (import và render component trong từng Plan). FEcore không thay đổi.

Preview cục bộ trong thư mục tests vốn được Git ignore: `EcoFood-FE/tests/plan-sale-preview.html`, `plan-sale-preview.jsx`. Preview dùng adapter giả lập hoàn toàn; không gọi backend thật hay lưu giao dịch/email. Chưa xác nhận giao diện bằng trình duyệt: browser access host không khả dụng và kiểm tra tự động từ chối đọc cửa sổ Chrome đang chứa trang cá nhân không liên quan. Không vượt qua quyết định đó; cửa sổ preview riêng không xuất hiện để điều khiển. Preview server đã được dừng.

Tài liệu mới: file này. Không sửa POM của người dùng, không thêm dependency, không commit/push.

## 7. Build/test

Build và toàn bộ unit test độc lập từ workspace:

```powershell
mvn -f .product-build/pom.xml package "-Dtest=*Test" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Khi build riêng từng repo, cần `mvn -f Eco_Core/pom.xml install` trước khi build backend. Trong môi trường agent, sử dụng cache Maven offline hiện có qua `-o "-Dmaven.repo.local=C:\Users\PC\.m2\repository"`.

Frontend:

```powershell
cd EcoFood-FE
npm run build
npm run lint
node --test
```

Kết quả đã kiểm tra:

- 37 test Core và 323 test backend độc lập pass; package Core/backend thành công sau khi bỏ POS registry.
- Frontend build pass; 34 test Node pass, gồm phân nhóm accordion, preview JSON lồng nhau, giữ default `false`/`0` và loại field POS cũ khỏi payload lưu.
- Frontend lint không có lỗi; 11 warning nằm ở các file Dashboard/Role/Tenant hiện hữu. Vite có warning bundle lớn hơn 500 kB.
- Đã chạy cả full suite mặc định. `AppApplicationTests.contextLoads` lỗi vì MongoDB `localhost:27017` không chạy; lỗi gốc ở bean `productIndexes`. Không sửa/bỏ test trong source và không báo full integration suite pass.
- Unit test mới không kết nối Mongo thật. Bao phủ hai Plan độc lập, một Orchestrator mỗi Tenant/Plan, kích hoạt không cần POS, quyền/scope, Draft/Active/Archived, revision conflicts, failed activation giữ Active cũ, schema/default/rule, HTTP 400/403/409, đọc document Mongo cũ có POS và runtime chuẩn bị đúng Active snapshot.
- Chưa nghiệm thu E2E giao dịch, Customer, email hay Dashboard vì các module thực thi không có trong source hiện tại.

Log: `.product-build/uc14-plan-unit-package.log` (package + unit), `uc14-plan-package.log` (full suite với contextLoads lỗi), `uc14-plan-fe-build.log`, `uc14-plan-fe-tests.log`, `uc14-plan-fe-lint.log`.

Log cho bản accordion/bỏ POS: `.product-build/uc14-accordion-unit-package.log`, `uc14-accordion-fe-build.log`, `uc14-accordion-fe-tests.log`, `uc14-accordion-fe-lint.log`.
