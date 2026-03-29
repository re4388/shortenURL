
## Code Style & Conventions
1. **Naming Conventions**:
    - 資料實體 (Entity) 統一使用 `PO` 作為後綴 (例如: `UrlMappingPO`, `WorkerNodePO`)。
2. **Architecture**:
    - 嚴格區分 Controller, Service, Repository 層次。
    - 資料庫主鍵 (Primary Key) 在 Snowflake 實作後統一使用 `Long` 類型。
3. **Documentation**:
    - 保持 `docs/project_setup.md` 與 `docs/snowflake_implementation.md` 的內容與最新程式碼同步。