// 统一 Console 验证入口也执行仓库级 CLI 与受管生命周期测试。
import '../../scripts/test/frontend-lifecycle.test.mjs';
import '../../scripts/test/frontend-orchestration.test.mjs';
import '../../scripts/test/frontend-cli.test.mjs';
import '../../scripts/test/https-edge-lifecycle.test.mjs';
import '../../scripts/test/local-development.test.mjs';
