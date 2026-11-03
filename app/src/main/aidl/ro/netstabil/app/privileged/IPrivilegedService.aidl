package ro.netstabil.app.privileged;

interface IPrivilegedService {
    void destroy() = 16777114;
    int getUid() = 1;
    String execCommand(String command, int timeoutSec) = 2;
    String probeModemCapabilities(int subId, int simSlot) = 3;
    String setPreferredRatMode(int subId, int simSlot, String modeCode) = 4;
    String lockLteBands(int subId, int simSlot, in int[] bands) = 5;
    String lockLteCell(int subId, int simSlot, int earfcn, int pci) = 6;
    String unlockAllModemLocks(int subId, int simSlot) = 7;
    String applyCarrierConfigBoost(int subId, int simSlot, boolean enable) = 8;
    String setDualSimAutoSwitch(boolean enable) = 9;
}
