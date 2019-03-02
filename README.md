# 透過K8S的API來設計工具或應用程式 (Java)

Kubernetes是一個強大的平台，您可以創建各種工具和客戶。幸運的是，在針對Kubernetes API進行編程時，
有很多選擇。不幸的是，這些選項可能會被大量的API壓倒，這可能會讓一些程式開發人員無所事從。

本範示展示了Kubernetes作為平台的可擴展性。雖然此處介紹的概念可以應用於任何可以訪問Kubernetes API的語言，
但這個Repo的討論和程式碼範例都集中在Java語言上。

## 前置準備

要能夠正確打包PVCWatch, 必需要有以下元件安裝:
* Maven 3
* OpenJDK 8

## 使用Maven來生成PVCWatch的執行Jar檔
```bash
mvn clean
mvn package
```

順利打包程式之後, 在"target"的目錄下會生成一個包含相關所需函式庫的Jar檔"pvcwatch-1.0-jar-with-dependencies.jar"。

讓我們直接執行:
```bash
java -jar target/pvcwatch-1.0-jar-with-dependencies.jar


connecting to API server https://localhost:6443

----- PVCs ----
Name                    Volume                                          Size

----- PVC Watch (max total claims: 2Gi) -----
```

讓再開一個terminal來產生一個50Mb的PVC到Kubernets的集群中
```bash
$ kubectl create -f kubernetes/pvc-test-50mb.yaml

persistentvolumeclaim/pvc-test-50mb created
```

這時候在原本執行PVCWatch的terminal裡會看到:
```bash
ADDED: PVC pvc-test-50mb added, size 50Mi
INFO: Total PVC is at  2.4% capacity (50Mi/2Gi)
MODIFIED: PVC pvc-test-50mb
INFO: Total PVC is at  2.4% capacity (50Mi/2Gi)
```

為了讓PVC的總量超過閥值(2Gb), 讓我們繼續產生另外一個PVC:
```bash
$ kubectl create -f kubernetes/pvc-test-2gb.yaml

persistentvolumeclaim/pvc-test-2gb created
```

這時候再回去看PVCWatch的terminal:
```bash
ADDED: PVC pvc-test-2gb added, size 2Gi
WARNING: claim overage reached: max 2Gi, at 2098Mi
*** Trigger over capacity action ***
INFO: Total PVC is at 102.4% capacity (2098Mi/2Gi)
```

PVCWatch如同我們預期地偵測到PVC的使用總量超出了預設的閥值(2Gi), 所以打印出Warning的訊息出來。

## 使用 Jib 生成 Java Docker 鏡像

Jib 是Google最新開源的Java應用程式的Docker鏡像生成工具，可以通過Gradle或Maven直接生成鏡像
並上傳到Docker鏡像倉庫而不需要Dockerfile文件或者其他插件；Jib 支持將資源文件和類分層打包，可以
大幅度提升生成鏡像的速度。

執行以下命令來把PVCWatch的工具程式容器化：

```bash
$ mvn compile jib:dockerBuild
```

檢查Docker鏡像是否生成成功:
```bash
$ docker images | grep pvcwatch

pvcwatch          1.0                 4c94655501ab        8 seconds ago       157MB
pvcwatch          latest              4c94655501ab        8 seconds ago       157MB
```

## 步署PVCWatch到Kubernetes集群中
```bash
$ kubectl create -f kubernetes/pod-pvcwatch.yaml

pod/pvcwatch created
```

檢查這個Pod的log:
```bash
$ kubectl logs pod/pvcwatch


connecting to API server https://10.96.0.1:443

----- PVCs ----
Name                    Volume                                          Size
pvc-test-2gb            pvc-ee1f21c7-3cf7-11e9-a6ed-025000000001        2Gi
pvc-test-50mb           pvc-b2e345ef-3cf6-11e9-a6ed-025000000001        50Mi

----- PVC Watch (max total claims: 2Gi) -----
ADDED: PVC pvc-test-50mb added, size 50Mi
INFO: Total PVC is at  2.4% capacity (50Mi/2Gi)
ADDED: PVC pvc-test-2gb added, size 2Gi
WARNING: claim overage reached: max 2Gi, at 2098Mi
*** Trigger over capacity action ***

```

