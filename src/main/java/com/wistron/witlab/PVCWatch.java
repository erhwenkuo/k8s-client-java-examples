package com.wistron.witlab;

import com.google.gson.reflect.TypeToken;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.models.V1PersistentVolumeClaim;
import io.kubernetes.client.models.V1PersistentVolumeClaimList;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

public class PVCWatch {
    public static void main(String[] args) throws IOException {
        // 取得想要監控的namesapce
        String namespace = System.getenv("K8S_NAMESPACE");

        // 如果沒有設定則使用"default"的namespace
        if (namespace == null || namespace.isEmpty()) {
            namespace = "default";
        }

        // 設定要監控的PVC的總量閥值
        Quantity maxClaims = Quantity.fromString("2Gi");
        Quantity totalClaims = Quantity.fromString("0Gi");

        // 取得ApiClient的實例
        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);

        // client.setDebugging(true); // 啟動debug

        // 取得K8S的ApiServer的URL
        String apiServer = client.getBasePath();
        System.out.format("%nconnecting to API server %s %n%n", apiServer);

        // 設定長連接, 避免timeout
        client.getHttpClient().setReadTimeout(0, TimeUnit.SECONDS);

        // 使用"/api/v1"的K8S API
        CoreV1Api api = new CoreV1Api(client);

        // 取得特定namespace的所有PVC的列表
        V1PersistentVolumeClaimList list = null;

        try {
            list = api.listNamespacedPersistentVolumeClaim(namespace, null, null, null, null, null, null, null, null, null);
        } catch (ApiException e) {
            System.err.println("Exception when calling CoreV1Api#listNamespacedPersistentVolumeClaim");
            e.printStackTrace();
            System.exit(1);
        }

        // 打印出現在的PVC claim的列表
        printPVCs(list);

        // 開始監控PVC的相關events
        System.out.format("%n----- PVC Watch (max total claims: %s) -----", maxClaims.toSuffixedString());

        try {
            // 啟動Watch來監控PVC的事件
            Watch<V1PersistentVolumeClaim> watch = Watch.createWatch(
                    client,
                    api.listNamespacedPersistentVolumeClaimCall(namespace, null, null, null, null, null, null, null, null, Boolean.TRUE, null, null),
                    new TypeToken<Watch.Response<V1PersistentVolumeClaim>>(){}.getType()
            );

            // 計算total PVC的sizes
            for (Watch.Response<V1PersistentVolumeClaim> item : watch) {
                V1PersistentVolumeClaim pvc = item.object;

                String claimSize = null;
                Quantity claimQuant = null;
                BigDecimal totalNum = null;

                // 一個PVC的生命週期事件有: ADDED, MODIFIED 及 DELETED
                switch (item.type) {
                    case "ADDED":

                        claimQuant = pvc.getSpec().getResources().getRequests().get("storage");
                        claimSize = claimQuant.toSuffixedString(); // 取得有數量單位的後綴, 比如: 5Gi
                        totalNum = totalClaims.getNumber().add(claimQuant.getNumber()); // 總量+新增量
                        totalClaims = new Quantity(totalNum, Quantity.Format.BINARY_SI);

                        System.out.format("%nADDED: PVC %s added, size %s", pvc.getMetadata().getName(), claimSize);

                        // 檢查PVC的總量是否有超過閥值
                        if (totalClaims.getNumber().compareTo(maxClaims.getNumber()) >= 1) {
                            System.out.format("%nWARNING: claim overage reached: max %s, at %s",
                                    maxClaims.toSuffixedString(),
                                    totalClaims.toSuffixedString());

                            System.out.format("%n*** Trigger over capacity action ***");
                        }
                        break;

                    case "MODIFIED":
                        System.out.format("%nMODIFIED: PVC %s", pvc.getMetadata().getName());
                        break;

                    case "DELETED":
                        claimQuant = pvc.getSpec().getResources().getRequests().get("storage");
                        claimSize = claimQuant.toSuffixedString(); // 取得有數量單位的後綴, 比如: 5Gi
                        totalNum = totalClaims.getNumber().subtract(claimQuant.getNumber()); // 總量-移除量
                        totalClaims = new Quantity(totalNum, Quantity.Format.BINARY_SI);

                        System.out.format("%nDELETED: PVC %s removed, size %s",
                                pvc.getMetadata().getName(),
                                claimSize);

                        // 檢查PVC的總量是否"沒有"超過閥值
                        if (totalClaims.getNumber().compareTo(maxClaims.getNumber()) <= 0) {
                            System.out.format(
                                    "%nINFO: claim usage normal: max %s, at %s",
                                    maxClaims.toSuffixedString(), totalClaims.toSuffixedString()
                            );
                        }
                        break;
                } // END of Switch

                // 打印出現在"PVC的總量"與"設定閥值"的比率
                System.out.format(
                        "%nINFO: Total PVC is at %4.1f%% capacity (%s/%s)",
                        (totalClaims.getNumber().floatValue()/maxClaims.getNumber().floatValue()) * 100,
                        totalClaims.toSuffixedString(),
                        maxClaims.toSuffixedString()
                );
            }

        } catch(ApiException e) {
            System.err.println("Exception watching PersistentVolumeClaims");
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void printPVCs(V1PersistentVolumeClaimList llist) {
        System.out.println("----- PVCs ----");
        String template = "%-16s\t%-40s\t%-6s%n";
        System.out.format(template, "Name", "Volume", "Size");

        for (V1PersistentVolumeClaim item : llist.getItems()) {
            String name = item.getMetadata().getName();
            String volumeName = item.getSpec().getVolumeName();
            String size = item.getSpec().getResources().getRequests().get("storage").toSuffixedString();
            System.out.format(template, name, volumeName, size);
        }
    }
}
