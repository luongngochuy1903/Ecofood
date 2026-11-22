package com.ecofood.app.product.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

import com.ecofood.app.auth.security.InternalJwtRequestFilter;
import com.ecofood.app.auth.service.InternalTokenService;
import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.product.entity.*;
import com.ecofood.app.product.repository.*;
import com.ecofood.app.product.service.*;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.service.ACLService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.*;
import javax.crypto.SecretKey;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Exercises HTTP binding, validation, JWT filter, ownership and lifecycle with mocked persistence. */
class ProductApiTest {
  private static final String SECRET = Base64.getEncoder()
      .encodeToString("product-api-test-secret-at-least-32-bytes".getBytes());
  private static final SecretKey KEY = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
  private final Map<ObjectId, ProductEntity> products = new HashMap<>();
  private final List<ProductProcessEntity> versions = new ArrayList<>();
  private final Map<ObjectId,ProductDefinitionAttribute> definitions=new HashMap<>();
  private final Map<ObjectId,ProductCostingAttribute> costings=new HashMap<>();
  private final Map<ObjectId,ProductOverviewAttribute> overviews=new HashMap<>();
  private final Map<ObjectId,ProductVersionEntity> legacyData=new HashMap<>();
  private ProductVersionRepository legacyRepository;
  private final ObjectId enterprise = new ObjectId();
  private final ObjectId tenant = new ObjectId();
  private MockMvc mvc;
  private String token;

  @BeforeEach void setup() {
    ProductRepository productRepository = mock(ProductRepository.class);
    ProductProcessRepository processRepository=mock(ProductProcessRepository.class);
    ProductDefinitionAttributeRepository definitionRepository=mock(ProductDefinitionAttributeRepository.class);
    ProductCostingAttributeRepository costingRepository=mock(ProductCostingAttributeRepository.class);
    ProductOverviewAttributeRepository overviewRepository=mock(ProductOverviewAttributeRepository.class);
    legacyRepository=mock(ProductVersionRepository.class);
    when(legacyRepository.findByProductIdAndVersion(any(),anyInt())).thenAnswer(i -> legacyData.values().stream().filter(v -> v.getProductId().equals(i.getArgument(0))&&v.getVersion()==(int)i.getArgument(1)).findFirst());
    when(legacyRepository.findByProductIdOrderByVersionDesc(any())).thenAnswer(i -> legacyData.values().stream().filter(v -> v.getProductId().equals(i.getArgument(0))).sorted(Comparator.comparingInt(ProductVersionEntity::getVersion).reversed()).toList());
    when(definitionRepository.insert(any(ProductDefinitionAttribute.class))).thenAnswer(i->{ProductDefinitionAttribute a=i.getArgument(0);definitions.put(a.getId(),a);return a;});
    when(costingRepository.insert(any(ProductCostingAttribute.class))).thenAnswer(i->{ProductCostingAttribute a=i.getArgument(0);costings.put(a.getId(),a);return a;});
    when(overviewRepository.insert(any(ProductOverviewAttribute.class))).thenAnswer(i->{ProductOverviewAttribute a=i.getArgument(0);overviews.put(a.getId(),a);return a;});
    when(definitionRepository.findById(any())).thenAnswer(i -> Optional.ofNullable(definitions.get(i.getArgument(0))));
    when(costingRepository.findById(any())).thenAnswer(i -> Optional.ofNullable(costings.get(i.getArgument(0))));
    when(overviewRepository.findById(any())).thenAnswer(i -> Optional.ofNullable(overviews.get(i.getArgument(0))));
    ProductProcessStore versionRepository=new ProductProcessStore(processRepository,definitionRepository,costingRepository,overviewRepository,legacyRepository,new ProductProcessSnapshots(new ProductCostCalculator()));
    CoreTenantQueryClient tenants = mock(CoreTenantQueryClient.class);
    when(tenants.getById(tenant.toHexString())).thenReturn(TenantResponse.builder()
        .enterpriseId(enterprise.toHexString()).build());
    when(productRepository.save(any())).thenAnswer(i -> {
      ProductEntity p = i.getArgument(0);
      p.setRevision(p.getRevision() == null ? 0L : p.getRevision() + 1);
      products.put(p.getId(), p);
      return p;
    });
    when(productRepository.findById(any())).thenAnswer(i -> Optional.ofNullable(products.get(i.getArgument(0))));
    when(productRepository.findByEnterpriseId(any())).thenAnswer(i -> products.values().stream()
        .filter(p -> p.getEnterpriseId().equals(i.getArgument(0))).toList());
    when(productRepository.findByEnterpriseIdAndCode(any(),anyString())).thenAnswer(i -> products.values().stream()
        .filter(p -> p.getEnterpriseId().equals(i.getArgument(0)) && Objects.equals(p.getCode(),i.getArgument(1))).findFirst());
    when(processRepository.save(any())).thenAnswer(i -> {
      ProductProcessEntity v = i.getArgument(0);
      v.setRevision(v.getRevision() == null ? 0L : v.getRevision() + 1);
      versions.removeIf(stored -> stored.getId().equals(v.getId()));versions.add(storedProcess(v));
      return v;
    });
    when(processRepository.insert(any(ProductProcessEntity.class))).thenAnswer(i->{ProductProcessEntity v=i.getArgument(0);versions.add(storedProcess(v));return v;});
    when(processRepository.findByProductIdAndVersion(any(), anyInt())).thenAnswer(i -> versions.stream()
        .filter(v -> v.getProductId().equals(i.getArgument(0)) && v.getVersion() == (int) i.getArgument(1)).findFirst().map(this::storedProcess));
    when(processRepository.findByProductIdOrderByVersionDesc(any())).thenAnswer(i -> versions.stream()
        .filter(v -> v.getProductId().equals(i.getArgument(0)))
        .sorted(Comparator.comparingInt(ProductProcessEntity::getVersion).reversed()).map(this::storedProcess).toList());
    ProductAuthorization authorization = new ProductAuthorization(new ACLService(), tenants);
    ProductServiceImpl service = new ProductServiceImpl(productRepository, versionRepository, authorization,
        new ProductValidation(), mock(ProductAuditService.class), mock(com.ecofood.app.product.service.ProductVersionReviewService.class));
    ProductQueryServiceImpl query = new ProductQueryServiceImpl(productRepository, versionRepository, authorization);
    InternalTokenService tokens = mock(InternalTokenService.class);
    when(tokens.verifyToken(anyString())).thenAnswer(i -> Jwts.parser().verifyWith(KEY).build()
        .parseSignedClaims((String) i.getArgument(0)).getPayload());
    InternalJwtRequestFilter filter = new InternalJwtRequestFilter(tokens);
    ReflectionTestUtils.setField(filter, "jwtSecret", SECRET);
    ReflectionTestUtils.setField(filter, "expectedIssuer", "app");
    ReflectionTestUtils.setField(filter, "expectedAudience", "app-api");
    mvc = MockMvcBuilders.standaloneSetup(new ProductController(service, query))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
        .addFilters(filter).build();
    token = token("ENTERPRISE");
  }

  @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
  // Mimics persisted fields only; every read must hydrate detail fields from typed attributes.
  private ProductProcessEntity storedProcess(ProductProcessEntity value) {
    ProductProcessEntity copy=ProductProcessEntity.builder().productId(value.getProductId()).version(value.getVersion())
        .revision(value.getRevision()).status(value.getStatus()).processElementName(value.getProcessElementName())
        .definition(value.getDefinition()).costingProcess(value.getCostingProcess()).overview(value.getOverview())
        .approvedBy(value.getApprovedBy()).approvedAt(value.getApprovedAt()).rejectionReason(value.getRejectionReason()).build();
    copy.setId(value.getId());copy.setCreatedAt(value.getCreatedAt());copy.setUpdatedAt(value.getUpdatedAt());return copy;
  }

  private String token(String role) {
    return Jwts.builder().subject(new ObjectId().toHexString()).id(UUID.randomUUID().toString())
          .claim("tenantId", tenant.toHexString()).claim("roleGroups", List.of(role)).claim("roles", List.of(role+"_ADMIN"))
        .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(KEY).compact();
  }

  private String createProduct() throws Exception {
    mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content("""
          {"type":"STANDALONE","name":"Milk","code":"MILK","category":"Drinks","ingredients":[],
           "pricing":{"sellingPrice":120000,"costPrice":85000,"currency":"VND"}}
          """))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"));
    return products.keySet().iterator().next().toHexString();
  }

  @Test void allNineEndpointsSupportReviewAndVersionWorkflow() throws Exception {
    String id = createProduct();
    String path = "/api/v1/products/" + id;
    mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token)
        .param("search", "milk").param("type", "STANDALONE").param("status", "DRAFT"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get(path).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0));
    String update = """
        {"name":"Milk 1L","code":"MILK","category":"Drinks","ingredients":[],"expectedRevision":0,
         "pricing":{"sellingPrice":120000,"costPrice":85000,"currency":"VND"}}
        """;
    mvc.perform(put(path).header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content(update))
        .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
    mvc.perform(put(path).header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isConflict());
    mvc.perform(post(path + "/submit").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    mvc.perform(post(path + "/reject").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Review ingredients\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
    mvc.perform(put(path).header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content(update.replace("\"expectedRevision\":0", "\"expectedRevision\":3")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
    mvc.perform(post(path + "/submit").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    mvc.perform(post(path + "/approve").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    mvc.perform(post(path + "/versions").header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.status").value("DRAFT"));
    mvc.perform(get(path + "/versions").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].version").value(2))
        .andExpect(jsonPath("$[1].status").value("APPROVED"));
  }

  @Test void wizardDefinitionIngredientsRecipeAndPricingRoundTrip() throws Exception {
    mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content("""
          {"type":"STANDALONE","name":"Chicken","code":"ch-01","category":"Meals",
           "imageUrl":"https://example.com/chicken.jpg",
           "ingredients":[{"name":"Chicken","quantity":500,"unit":"g","note":"Fresh"}],
           "recipe":{"enabled":true,"name":"Boil","preparationMinutes":10,"cookingMinutes":20,
             "note":"Serve hot","steps":[{"order":1,"name":"Boil chicken","description":"Simmer","durationMinutes":20}]},
           "pricing":{"sellingPrice":100000,"currency":"VND","note":"Basic price"}}
          """))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("CH-01"))
        .andExpect(jsonPath("$.ingredients[0].name").value("Chicken"))
        .andExpect(jsonPath("$.recipe.preparationMinutes").value(10))
        .andExpect(jsonPath("$.pricing.note").value("Basic price"));
    String id = products.keySet().iterator().next().toHexString();
    mvc.perform(post("/api/v1/products/" + id + "/submit").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token).param("search","ch-01"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content("""
          {"type":"STANDALONE","name":"Duplicate","code":"CH-01","category":"Meals"}
          """))
        .andExpect(status().isConflict());
  }
  @Test void adaptedAndMasterRelationshipsRoundTrip() throws Exception {
    String base = createProduct();
    mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content("""
          {"type":"STANDALONE","name":"Juice","code":"JUICE","category":"Drinks"}
          """)).andExpect(status().isCreated());
    String drink=products.values().stream().filter(p -> "JUICE".equals(p.getCode())).findFirst().orElseThrow().getIdAsString();
    mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content("""
          {"type":"ADAPTED","name":"Invalid base","code":"INVALID","category":"Drinks","sourceProductId":"%s"}
          """.formatted(base))).andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content("""
          {"type":"MASTER","name":"Base combo","code":"BASE","category":"Combos",
           "componentProducts":[{"productId":"%s","quantity":1},{"productId":"%s","quantity":1}],
           "pricing":{"sellingPrice":130000,"currency":"VND"}}
          """.formatted(base,drink))).andExpect(status().isCreated());
    String source=products.values().stream().filter(p -> "BASE".equals(p.getCode())).findFirst().orElseThrow().getIdAsString();
    String adaptedBody="""
          {"type":"ADAPTED","name":"Flavoured combo","code":"ADAPTED","category":"Drinks","sourceProductId":"%s"}
          """.formatted(source);
    mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content(adaptedBody)).andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/products/"+source+"/submit").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    mvc.perform(post("/api/v1/products/"+source+"/approve").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content(adaptedBody))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.sourceProductId").value(source));
    String adapted = products.values().stream().filter(p -> "ADAPTED".equals(p.getCode())).findFirst().orElseThrow().getIdAsString();
    mvc.perform(post("/api/v1/products/"+source+"/versions").header("Authorization","Bearer "+token)).andExpect(status().isCreated());
    mvc.perform(put("/api/v1/products/"+source).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("""
        {"name":"Cycle","code":"BASE","category":"Combos","expectedRevision":0,
         "componentProducts":[{"productId":"%s","quantity":1},{"productId":"%s","quantity":1}]}
        """.formatted(base,adapted))).andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content("""
          {"type":"MASTER","name":"Milk combo","code":"COMBO","category":"Combos",
           "componentProducts":[{"productId":"%s","quantity":1},{"productId":"%s","quantity":2}],
           "pricing":{"sellingPrice":130000,"currency":"VND"}}
          """.formatted(base,adapted)))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.componentProducts[1].quantity").value(2));
    String combo = products.values().stream().filter(p -> "COMBO".equals(p.getCode())).findFirst().orElseThrow().getIdAsString();
    mvc.perform(post("/api/v1/products/" + combo + "/submit").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/products/" + combo + "/approve").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/products/" + combo + "/versions").header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.componentProducts.length()").value(2))
        .andExpect(jsonPath("$.category").value("Combos")).andExpect(jsonPath("$.code").value("COMBO"));
  }
  @Test void currencySurvivesDraftReadUpdateAndSubmission() throws Exception {
    mvc.perform(post("/api/v1/products").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("""
      {"type":"STANDALONE","name":"Coffee","code":"COFFEE","category":"Drinks",
       "costing":{"ingredientCost":2.5,"currency":"USD"}}
      """))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.costing.currency").value("USD"))
        .andExpect(jsonPath("$.pricing").doesNotExist());
    String base="/api/v1/products/"+products.values().iterator().next().getIdAsString();
    mvc.perform(get(base).header("Authorization","Bearer "+token))
        .andExpect(status().isOk()).andExpect(jsonPath("$.costing.currency").value("USD"));
    mvc.perform(put(base).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("""
      {"name":"Coffee","code":"COFFEE","category":"Drinks","expectedRevision":0,
       "costing":{"ingredientCost":2.5,"currency":"USD"},"pricing":{"sellingPrice":5.5,"currency":"USD"}}
      """))
        .andExpect(status().isOk()).andExpect(jsonPath("$.pricing.currency").value("USD"))
        .andExpect(jsonPath("$.costing.currency").value("USD"));
    mvc.perform(post(base+"/submit").header("Authorization","Bearer "+token))
        .andExpect(status().isOk()).andExpect(jsonPath("$.pricing.currency").value("USD"))
        .andExpect(jsonPath("$.productProcess[0].overview.processElementAttribute[0].currency").value("USD"));
  }
  @Test void processesPersistTypedAttributesAndRetainApprovedVersionAfterNewDraft() throws Exception {
    mvc.perform(post("/api/v1/products").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("""
      {"type":"STANDALONE","name":"Chicken","code":"CHICKEN","category":"Meals",
       "costing":{"ingredientCost":50000,"packagingCost":5000,"laborCost":10000,"operationCost":3000,"marketingFee":2000,"targetMargin":30,"tax":10},
       "pricing":{"sellingPrice":120000,"costPrice":999999,"currency":"VND"}}
      """))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.costing.totalCost").value(70000))
        .andExpect(jsonPath("$.costing.basePrice").value(110000)).andExpect(jsonPath("$.pricing.costPrice").value(70000))
        .andExpect(jsonPath("$.productProcess[0].definition.processElementAttribute[0].productName").value("Chicken"))
        .andExpect(jsonPath("$.productProcess[0].overview.processElementAttribute[0].totalCost").value(70000));
    assertThat(definitions).hasSize(1);assertThat(costings).hasSize(1);assertThat(overviews).hasSize(1);
    ProductEntity root=products.values().iterator().next();String base="/api/v1/products/"+root.getIdAsString();
    assertThat(root.getProductProcess()).containsExactly(versions.get(0).getId());
    assertThat(root.getCategory()).containsExactly("Meals");
    mvc.perform(post(base+"/submit").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    mvc.perform(post(base+"/approve").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    mvc.perform(post(base+"/versions").header("Authorization","Bearer "+token)).andExpect(status().isCreated());
    mvc.perform(put(base).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("""
      {"name":"New chicken","code":"CHICKEN","category":"Meals","expectedRevision":0,
       "costing":{"ingredientCost":80000},"pricing":{"sellingPrice":140000,"currency":"VND"}}
      """))
        .andExpect(status().isOk()).andExpect(jsonPath("$.costing.totalCost").value(80000));
    mvc.perform(get(base+"/processes/1").header("Authorization","Bearer "+token))
        .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Chicken"))
        .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.costing.totalCost").value(70000));
    mvc.perform(get(base).header("Authorization","Bearer "+token))
        .andExpect(status().isOk()).andExpect(jsonPath("$.productProcess.length()").value(2))
        .andExpect(jsonPath("$.productProcess[0].overview.processElementAttribute[0].productName").value("New chicken"));
    mvc.perform(get(base+"/processes").header("Authorization","Bearer "+token))
        .andExpect(status().isOk()).andExpect(jsonPath("$[1].approvedBy").isNotEmpty());
    assertThat(root.getProductProcess()).hasSize(2);
    verify(legacyRepository,never()).save(any());verify(legacyRepository,never()).deleteById(any());
  }
  @Test void legacyReadAndExplicitEditKeepLegacyDataAndPublishNewAttributes() throws Exception {
    ObjectId productId=new ObjectId();
    ProductEntity product=ProductEntity.builder().enterpriseId(enterprise).code("OLD").type(com.ecofood.core.enums.ProductTypeEnum.STANDALONE).latestVersion(1).build();
    product.setId(productId);products.put(productId,product);
    ProductVersionEntity old=ProductVersionEntity.builder().productId(productId).version(1).revision(3L)
        .status(com.ecofood.core.enums.ProductStatusEnum.DRAFT).name("Old product").code("OLD").category("Meals").build();
    // Persisted legacy records include audit fields populated by Mongo auditing.
    ObjectId actorId=new ObjectId();old.setCreatedBy(actorId);old.setUpdatedBy(actorId);
    old.setId(new ObjectId());legacyData.put(old.getId(),old);
    String base="/api/v1/products/"+productId.toHexString();
    mvc.perform(get(base).header("Authorization","Bearer "+token)).andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Old product")).andExpect(jsonPath("$.productProcess[0].legacy").value(true));
    assertThat(versions).isEmpty();assertThat(definitions).isEmpty();
    mvc.perform(put(base).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("""
      {"name":"Updated old product","code":"OLD","category":"Meals","expectedRevision":3,"costing":{"ingredientCost":12000}}
      """))
        .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(4))
        .andExpect(jsonPath("$.productProcess[0].legacy").value(false));
    assertThat(old.getName()).isEqualTo("Old product");assertThat(old.getRevision()).isEqualTo(3L);
    assertThat(definitions).hasSize(1);assertThat(product.getProductProcess()).containsExactly(old.getId());
    mvc.perform(get(base).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Updated old product"));
    verify(legacyRepository,never()).save(any());verify(legacyRepository,never()).deleteById(any());
  }
  @Test void anonymousInvalidTokenAndNonEnterpriseAreRejected() throws Exception {
    mvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/products").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token("TENANT")))
        .andExpect(status().isForbidden());
  }

  @Test void foreignProductIsHiddenAndInvalidPayloadReturns400() throws Exception {
    String id = createProduct();
    products.get(new ObjectId(id)).setEnterpriseId(new ObjectId());
    mvc.perform(get("/api/v1/products/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/products/"+id+"/processes").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/products/"+id+"/processes/1").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"MASTER\",\"name\":\" \"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content("""
          {"type":"MASTER","name":"Milk","ingredients":[{"ingredientId":"a","quantity":-1,"unit":"ML"}]}
          """))
        .andExpect(status().isBadRequest());
  }
}
