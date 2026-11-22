package com.retail.agent.store;

import com.retail.agent.model.CycleCountRecord;
import com.retail.agent.model.ProductSku;
import com.retail.agent.model.ReorderPolicy;
import com.retail.agent.model.StockLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe, in-memory implementation of {@link RetailInventoryStore} populated
 * with realistic enterprise retail data across multiple warehouses, product categories,
 * replenishment policies, and historical cycle count audits.
 */
public class InMemoryRetailInventoryStore implements RetailInventoryStore {

    private final ConcurrentMap<String, ProductSku> catalog = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StockLevel> stockMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReorderPolicy> policyMap = new ConcurrentHashMap<>();
    private final List<CycleCountRecord> cycleCountRecords = new CopyOnWriteArrayList<>();
    private final Set<String> warehouses = ConcurrentHashMap.newKeySet();

    /**
     * Constructs a store and seeds it with enterprise sample inventory data.
     */
    public InMemoryRetailInventoryStore() {
        this(true);
    }

    /**
     * Constructs a store, optionally seeding initial sample data.
     *
     * @param seedData whether to populate standard retail inventory data
     */
    public InMemoryRetailInventoryStore(boolean seedData) {
        if (seedData) {
            populateSeedData();
        }
    }

    /**
     * Static factory method to instantiate a pre-populated retail store.
     */
    public static InMemoryRetailInventoryStore createWithDefaultData() {
        return new InMemoryRetailInventoryStore(true);
    }

    /**
     * Static factory method to instantiate an empty retail store for custom testing.
     */
    public static InMemoryRetailInventoryStore createEmpty() {
        return new InMemoryRetailInventoryStore(false);
    }

    @Override
    public Optional<ProductSku> findProductBySku(String sku) {
        if (sku == null || sku.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(catalog.get(normalize(sku)));
    }

    @Override
    public List<ProductSku> getAllProducts() {
        return List.copyOf(catalog.values());
    }

    @Override
    public List<ProductSku> findProductsByCategory(String category) {
        if (category == null || category.isBlank()) {
            return List.of();
        }
        String normalizedCategory = category.trim().toLowerCase(Locale.ROOT);
        return catalog.values().stream()
                .filter(p -> p.category().toLowerCase(Locale.ROOT).contains(normalizedCategory))
                .toList();
    }

    @Override
    public Optional<StockLevel> getStockLevel(String sku, String warehouseId) {
        if (sku == null || warehouseId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(stockMap.get(compositeKey(sku, warehouseId)));
    }

    @Override
    public List<StockLevel> getStockLevelsBySku(String sku) {
        if (sku == null || sku.isBlank()) {
            return List.of();
        }
        String normSku = normalize(sku);
        return stockMap.values().stream()
                .filter(s -> normalize(s.sku()).equals(normSku))
                .sorted(Comparator.comparing(StockLevel::warehouseId))
                .toList();
    }

    @Override
    public List<StockLevel> getStockLevelsByWarehouse(String warehouseId) {
        if (warehouseId == null || warehouseId.isBlank()) {
            return List.of();
        }
        String normWarehouse = normalize(warehouseId);
        return stockMap.values().stream()
                .filter(s -> normalize(s.warehouseId()).equals(normWarehouse))
                .sorted(Comparator.comparing(StockLevel::sku))
                .toList();
    }

    @Override
    public Optional<ReorderPolicy> getReorderPolicy(String sku, String warehouseId) {
        if (sku == null || warehouseId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(policyMap.get(compositeKey(sku, warehouseId)));
    }

    @Override
    public List<ReorderPolicy> getReorderPoliciesByWarehouse(String warehouseId) {
        if (warehouseId == null || warehouseId.isBlank()) {
            return List.of();
        }
        String normWarehouse = normalize(warehouseId);
        return policyMap.values().stream()
                .filter(p -> normalize(p.warehouseId()).equals(normWarehouse))
                .sorted(Comparator.comparing(ReorderPolicy::sku))
                .toList();
    }

    @Override
    public List<CycleCountRecord> getCycleCountHistory(String sku, String warehouseId) {
        if (sku == null || warehouseId == null) {
            return List.of();
        }
        String normSku = normalize(sku);
        String normWarehouse = normalize(warehouseId);

        return cycleCountRecords.stream()
                .filter(c -> normalize(c.sku()).equals(normSku)
                        && normalize(c.warehouseId()).equals(normWarehouse))
                .sorted(Comparator.comparing(CycleCountRecord::countTimestamp).reversed())
                .toList();
    }

    @Override
    public List<CycleCountRecord> getCycleCountsByWarehouse(String warehouseId) {
        if (warehouseId == null || warehouseId.isBlank()) {
            return List.of();
        }
        String normWarehouse = normalize(warehouseId);
        return cycleCountRecords.stream()
                .filter(c -> normalize(c.warehouseId()).equals(normWarehouse))
                .sorted(Comparator.comparing(CycleCountRecord::countTimestamp).reversed())
                .toList();
    }

    @Override
    public List<String> getAvailableWarehouses() {
        List<String> list = new ArrayList<>(warehouses);
        Collections.sort(list);
        return Collections.unmodifiableList(list);
    }

    @Override
    public void updateStock(String sku, String warehouseId, int onHand, int reserved) {
        Objects.requireNonNull(sku, "SKU cannot be null");
        Objects.requireNonNull(warehouseId, "Warehouse ID cannot be null");

        String normSku = normalize(sku);
        String normWarehouse = normalize(warehouseId);

        if (!catalog.containsKey(normSku)) {
            throw new IllegalArgumentException("Unknown SKU: " + sku);
        }

        StockLevel updatedStock = new StockLevel(sku, warehouseId, onHand, reserved);
        stockMap.put(compositeKey(normSku, normWarehouse), updatedStock);
        warehouses.add(warehouseId);
    }

    @Override
    public void recordCycleCount(CycleCountRecord record) {
        Objects.requireNonNull(record, "CycleCountRecord cannot be null");
        cycleCountRecords.add(record);
        warehouses.add(record.warehouseId());
    }

    @Override
    public void registerProduct(ProductSku product) {
        Objects.requireNonNull(product, "ProductSku cannot be null");
        catalog.put(normalize(product.sku()), product);
    }

    @Override
    public void setReorderPolicy(ReorderPolicy policy) {
        Objects.requireNonNull(policy, "ReorderPolicy cannot be null");
        String normSku = normalize(policy.sku());
        String normWarehouse = normalize(policy.warehouseId());

        if (!catalog.containsKey(normSku)) {
            throw new IllegalArgumentException("Cannot set reorder policy for unregistered SKU: " + policy.sku());
        }

        policyMap.put(compositeKey(normSku, normWarehouse), policy);
        warehouses.add(policy.warehouseId());
    }

    private String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String compositeKey(String sku, String warehouseId) {
        return normalize(sku) + "::" + normalize(warehouseId);
    }

    /**
     * Populates the in-memory store with enterprise retail data.
     */
    private void populateSeedData() {
        // --- 1. Warehouses ---
        String whCentral = "WH-CENTRAL-01"; // Dallas, TX
        String whEast = "WH-EAST-02";       // Allentown, PA
        String whWest = "WH-WEST-03";       // Reno, NV

        warehouses.add(whCentral);
        warehouses.add(whEast);
        warehouses.add(whWest);

        // --- 2. Products Catalog ---
        ProductSku p1 = new ProductSku(
                "SKU-ELEC-1001",
                "OmniView 55-Inch 4K OLED Smart TV",
                "Consumer Electronics",
                new BigDecimal("799.99"),
                "012345678901",
                "EACH"
        );
        ProductSku p2 = new ProductSku(
                "SKU-ELEC-1002",
                "AuraPro Wireless Noise-Cancelling Headphones",
                "Consumer Electronics",
                new BigDecimal("249.50"),
                "012345678902",
                "EACH"
        );
        ProductSku p3 = new ProductSku(
                "SKU-ELEC-1003",
                "VoltCharge 65W GaN Fast Wall Charger",
                "Consumer Electronics",
                new BigDecimal("39.99"),
                "012345678903",
                "EACH"
        );
        ProductSku p4 = new ProductSku(
                "SKU-APPR-2001",
                "EcoFiber Classic Cotton Crewneck T-Shirt (Navy/L)",
                "Apparel & Footwear",
                new BigDecimal("24.00"),
                "012345678904",
                "EACH"
        );
        ProductSku p5 = new ProductSku(
                "SKU-APPR-2002",
                "AllWeather Technical Waterproof Shell Jacket (Black/M)",
                "Apparel & Footwear",
                new BigDecimal("149.00"),
                "012345678905",
                "EACH"
        );
        ProductSku p6 = new ProductSku(
                "SKU-HOME-3001",
                "ChefPrecision 8-Piece Japanese Stainless Steel Knife Set",
                "Home & Kitchen",
                new BigDecimal("129.95"),
                "012345678906",
                "SET"
        );
        ProductSku p7 = new ProductSku(
                "SKU-HOME-3002",
                "AromaPure Ultrasonic Cool Mist Humidifier (4L)",
                "Home & Kitchen",
                new BigDecimal("59.99"),
                "012345678907",
                "EACH"
        );
        ProductSku p8 = new ProductSku(
                "SKU-GROC-4001",
                "Artisan Roast Whole Bean Coffee (1kg Colombian Supremo)",
                "Grocery & Consumables",
                new BigDecimal("18.50"),
                "012345678908",
                "BAG"
        );

        List.of(p1, p2, p3, p4, p5, p6, p7, p8).forEach(this::registerProduct);

        // --- 3. Stock Levels and Reorder Policies across Warehouses ---

        // SKU-ELEC-1001 (TV)
        // Central: Healthy (OnHand 140, Res 25, Avail 115 > ROP 40)
        stockMap.put(compositeKey("SKU-ELEC-1001", whCentral), new StockLevel("SKU-ELEC-1001", whCentral, 140, 25));
        policyMap.put(compositeKey("SKU-ELEC-1001", whCentral), new ReorderPolicy("SKU-ELEC-1001", whCentral, 40, 15, 100, 14));

        // East: Reorder Needed (OnHand 32, Res 12, Avail 20 <= ROP 25)
        stockMap.put(compositeKey("SKU-ELEC-1001", whEast), new StockLevel("SKU-ELEC-1001", whEast, 32, 12));
        policyMap.put(compositeKey("SKU-ELEC-1001", whEast), new ReorderPolicy("SKU-ELEC-1001", whEast, 25, 10, 50, 10));

        // West: Critical Safety Stock Breach (OnHand 8, Res 5, Avail 3 < Safety 8)
        stockMap.put(compositeKey("SKU-ELEC-1001", whWest), new StockLevel("SKU-ELEC-1001", whWest, 8, 5));
        policyMap.put(compositeKey("SKU-ELEC-1001", whWest), new ReorderPolicy("SKU-ELEC-1001", whWest, 20, 8, 50, 12));

        // SKU-ELEC-1002 (Headphones)
        // Central: Healthy (OnHand 320, Res 45, Avail 275 > ROP 100)
        stockMap.put(compositeKey("SKU-ELEC-1002", whCentral), new StockLevel("SKU-ELEC-1002", whCentral, 320, 45));
        policyMap.put(compositeKey("SKU-ELEC-1002", whCentral), new ReorderPolicy("SKU-ELEC-1002", whCentral, 100, 30, 200, 7));

        // East: Reorder Needed (OnHand 95, Res 30, Avail 65 <= ROP 80)
        stockMap.put(compositeKey("SKU-ELEC-1002", whEast), new StockLevel("SKU-ELEC-1002", whEast, 95, 30));
        policyMap.put(compositeKey("SKU-ELEC-1002", whEast), new ReorderPolicy("SKU-ELEC-1002", whEast, 80, 25, 150, 7));

        // West: Healthy (OnHand 180, Res 20, Avail 160 > ROP 75)
        stockMap.put(compositeKey("SKU-ELEC-1002", whWest), new StockLevel("SKU-ELEC-1002", whWest, 180, 20));
        policyMap.put(compositeKey("SKU-ELEC-1002", whWest), new ReorderPolicy("SKU-ELEC-1002", whWest, 75, 25, 150, 8));

        // SKU-ELEC-1003 (Fast Charger)
        // Central: Healthy (OnHand 850, Res 120, Avail 730 > ROP 250)
        stockMap.put(compositeKey("SKU-ELEC-1003", whCentral), new StockLevel("SKU-ELEC-1003", whCentral, 850, 120));
        policyMap.put(compositeKey("SKU-ELEC-1003", whCentral), new ReorderPolicy("SKU-ELEC-1003", whCentral, 250, 80, 500, 5));

        // East: Healthy (OnHand 420, Res 60, Avail 360 > ROP 200)
        stockMap.put(compositeKey("SKU-ELEC-1003", whEast), new StockLevel("SKU-ELEC-1003", whEast, 420, 60));
        policyMap.put(compositeKey("SKU-ELEC-1003", whEast), new ReorderPolicy("SKU-ELEC-1003", whEast, 200, 60, 400, 5));

        // West: Critical Safety Stock Breach (OnHand 70, Res 25, Avail 45 < Safety 50)
        stockMap.put(compositeKey("SKU-ELEC-1003", whWest), new StockLevel("SKU-ELEC-1003", whWest, 70, 25));
        policyMap.put(compositeKey("SKU-ELEC-1003", whWest), new ReorderPolicy("SKU-ELEC-1003", whWest, 150, 50, 300, 6));

        // SKU-APPR-2001 (T-Shirt)
        // Central: Healthy (OnHand 500, Res 50, Avail 450 > ROP 150)
        stockMap.put(compositeKey("SKU-APPR-2001", whCentral), new StockLevel("SKU-APPR-2001", whCentral, 500, 50));
        policyMap.put(compositeKey("SKU-APPR-2001", whCentral), new ReorderPolicy("SKU-APPR-2001", whCentral, 150, 50, 300, 10));

        // East: Healthy (OnHand 220, Res 40, Avail 180 > ROP 120)
        stockMap.put(compositeKey("SKU-APPR-2001", whEast), new StockLevel("SKU-APPR-2001", whEast, 220, 40));
        policyMap.put(compositeKey("SKU-APPR-2001", whEast), new ReorderPolicy("SKU-APPR-2001", whEast, 120, 40, 250, 10));

        // West: Reorder Needed (OnHand 110, Res 30, Avail 80 <= ROP 100)
        stockMap.put(compositeKey("SKU-APPR-2001", whWest), new StockLevel("SKU-APPR-2001", whWest, 110, 30));
        policyMap.put(compositeKey("SKU-APPR-2001", whWest), new ReorderPolicy("SKU-APPR-2001", whWest, 100, 30, 200, 10));

        // SKU-APPR-2002 (Jacket)
        // Central: Healthy (OnHand 75, Res 15, Avail 60 > ROP 50)
        stockMap.put(compositeKey("SKU-APPR-2002", whCentral), new StockLevel("SKU-APPR-2002", whCentral, 75, 15));
        policyMap.put(compositeKey("SKU-APPR-2002", whCentral), new ReorderPolicy("SKU-APPR-2002", whCentral, 50, 15, 100, 21));

        // East: Reorder Needed (OnHand 18, Res 8, Avail 10 <= ROP 30)
        stockMap.put(compositeKey("SKU-APPR-2002", whEast), new StockLevel("SKU-APPR-2002", whEast, 18, 8));
        policyMap.put(compositeKey("SKU-APPR-2002", whEast), new ReorderPolicy("SKU-APPR-2002", whEast, 30, 10, 60, 21));

        // West: Critical Safety Stock Breach (OnHand 5, Res 4, Avail 1 < Safety 8)
        stockMap.put(compositeKey("SKU-APPR-2002", whWest), new StockLevel("SKU-APPR-2002", whWest, 5, 4));
        policyMap.put(compositeKey("SKU-APPR-2002", whWest), new ReorderPolicy("SKU-APPR-2002", whWest, 25, 8, 60, 21));

        // SKU-HOME-3001 (Knife Set)
        // Central: Healthy (OnHand 120, Res 15, Avail 105 > ROP 40)
        stockMap.put(compositeKey("SKU-HOME-3001", whCentral), new StockLevel("SKU-HOME-3001", whCentral, 120, 15));
        policyMap.put(compositeKey("SKU-HOME-3001", whCentral), new ReorderPolicy("SKU-HOME-3001", whCentral, 40, 15, 80, 15));

        // East: Healthy (OnHand 60, Res 10, Avail 50 > ROP 35)
        stockMap.put(compositeKey("SKU-HOME-3001", whEast), new StockLevel("SKU-HOME-3001", whEast, 60, 10));
        policyMap.put(compositeKey("SKU-HOME-3001", whEast), new ReorderPolicy("SKU-HOME-3001", whEast, 35, 12, 50, 15));

        // West: Reorder Needed (OnHand 30, Res 5, Avail 25 <= ROP 30)
        stockMap.put(compositeKey("SKU-HOME-3001", whWest), new StockLevel("SKU-HOME-3001", whWest, 30, 5));
        policyMap.put(compositeKey("SKU-HOME-3001", whWest), new ReorderPolicy("SKU-HOME-3001", whWest, 30, 10, 50, 15));

        // SKU-HOME-3002 (Humidifier)
        // Central: Healthy (OnHand 210, Res 35, Avail 175 > ROP 70)
        stockMap.put(compositeKey("SKU-HOME-3002", whCentral), new StockLevel("SKU-HOME-3002", whCentral, 210, 35));
        policyMap.put(compositeKey("SKU-HOME-3002", whCentral), new ReorderPolicy("SKU-HOME-3002", whCentral, 70, 20, 150, 10));

        // East: Reorder Needed (OnHand 65, Res 15, Avail 50 <= ROP 50)
        stockMap.put(compositeKey("SKU-HOME-3002", whEast), new StockLevel("SKU-HOME-3002", whEast, 65, 15));
        policyMap.put(compositeKey("SKU-HOME-3002", whEast), new ReorderPolicy("SKU-HOME-3002", whEast, 50, 15, 100, 10));

        // West: Critical Safety Stock Breach (OnHand 12, Res 6, Avail 6 < Safety 15)
        stockMap.put(compositeKey("SKU-HOME-3002", whWest), new StockLevel("SKU-HOME-3002", whWest, 12, 6));
        policyMap.put(compositeKey("SKU-HOME-3002", whWest), new ReorderPolicy("SKU-HOME-3002", whWest, 40, 15, 100, 10));

        // SKU-GROC-4001 (Coffee)
        // Central: Healthy (OnHand 600, Res 80, Avail 520 > ROP 180)
        stockMap.put(compositeKey("SKU-GROC-4001", whCentral), new StockLevel("SKU-GROC-4001", whCentral, 600, 80));
        policyMap.put(compositeKey("SKU-GROC-4001", whCentral), new ReorderPolicy("SKU-GROC-4001", whCentral, 180, 60, 400, 7));

        // East: Healthy (OnHand 310, Res 40, Avail 270 > ROP 150)
        stockMap.put(compositeKey("SKU-GROC-4001", whEast), new StockLevel("SKU-GROC-4001", whEast, 310, 40));
        policyMap.put(compositeKey("SKU-GROC-4001", whEast), new ReorderPolicy("SKU-GROC-4001", whEast, 150, 50, 300, 7));

        // West: Reorder Needed (OnHand 140, Res 30, Avail 110 <= ROP 120)
        stockMap.put(compositeKey("SKU-GROC-4001", whWest), new StockLevel("SKU-GROC-4001", whWest, 140, 30));
        policyMap.put(compositeKey("SKU-GROC-4001", whWest), new ReorderPolicy("SKU-GROC-4001", whWest, 120, 40, 250, 7));

        // --- 4. Historical Cycle Count Records (Audits & Discrepancies) ---
        Instant now = Instant.now();

        // 1. Exact Match: SKU-ELEC-1001 @ Central (3 days ago)
        cycleCountRecords.add(new CycleCountRecord(
                "CC-REC-1001",
                "SKU-ELEC-1001",
                whCentral,
                140,
                140,
                now.minus(3, ChronoUnit.DAYS),
                "AUDITOR-JDOE",
                "Routine quarterly physical audit. Pallet seal 992-A intact. Variance zero."
        ));

        // 2. Shrinkage Loss: SKU-ELEC-1002 @ East (2 days ago, -7 units)
        cycleCountRecords.add(new CycleCountRecord(
                "CC-REC-1002",
                "SKU-ELEC-1002",
                whEast,
                95,
                102,
                now.minus(2, ChronoUnit.DAYS),
                "AUDITOR-ASMITH",
                "Discrepancy identified in aisle E4 bin 12. Master carton was opened and 7 units missing. Security notified."
        ));

        // 3. Shrinkage Loss (Damaged): SKU-APPR-2002 @ West (5 days ago, -3 units)
        cycleCountRecords.add(new CycleCountRecord(
                "CC-REC-1003",
                "SKU-APPR-2002",
                whWest,
                5,
                8,
                now.minus(5, ChronoUnit.DAYS),
                "AUDITOR-RJOHNSON",
                "Water leak from HVAC overhead in zone W-08 damaged 3 jackets. Written off to scrap inventory."
        ));

        // 4. Surplus Found: SKU-ELEC-1003 @ East (1 day ago, +5 units)
        cycleCountRecords.add(new CycleCountRecord(
                "CC-REC-1004",
                "SKU-ELEC-1003",
                whEast,
                420,
                415,
                now.minus(1, ChronoUnit.DAYS),
                "AUDITOR-ASMITH",
                "Unscanned delivery box from PO-8831 found placed in overstock mezzanine. Re-inducted into stock balance."
        ));

        // 5. Shrinkage Loss: SKU-HOME-3001 @ Central (7 days ago, -4 units)
        cycleCountRecords.add(new CycleCountRecord(
                "CC-REC-1005",
                "SKU-HOME-3001",
                whCentral,
                120,
                124,
                now.minus(7, ChronoUnit.DAYS),
                "AUDITOR-JDOE",
                "4 sets missing from primary pick slot. Suspected mispick during holiday clearance promotion."
        ));

        // 6. Surplus Found: SKU-GROC-4001 @ West (4 days ago, +2 units)
        cycleCountRecords.add(new CycleCountRecord(
                "CC-REC-1006",
                "SKU-GROC-4001",
                whWest,
                140,
                138,
                now.minus(4, ChronoUnit.DAYS),
                "AUDITOR-RJOHNSON",
                "Two unopened bags found in return staging area and restored to shelf bin G-09."
        ));

        // 7. Exact Match: SKU-APPR-2001 @ Central (10 days ago, variance 0)
        cycleCountRecords.add(new CycleCountRecord(
                "CC-REC-1007",
                "SKU-APPR-2001",
                whCentral,
                500,
                500,
                now.minus(10, ChronoUnit.DAYS),
                "AUDITOR-JDOE",
                "Annual comprehensive apparel count. Full bin count reconciled accurately."
        ));
    }
}
