<<<<<<<<<<<<<<json接口示例>>>>>>>>>>>>>
# TVBox

=== Source Code - Editing the app default settings ===

    //searchable:搜索开关	        0:关闭 1:启用
    //filterable:首页可选	        0:否 1:是
    //playerType:播放器类型	        0:系统 1:IJK 2:EXO
    //采集接口类型		        0:xml 1:json 3:jar 4:remote
    //parses解析类型		        0:嗅探,自带播放器 1:解析,返回直链
    //直播参数说明                   ua:用户自定义ua epg:节目网址 logo:台标网址
    
    {
	"spider": "./your.jar",
	"wallpaper": "./api/img",
	"sites": [],
	"parses": [],
	"hosts": [
		"cache.ott.ystenlive.itv.cmvideo.cn=base-v4-free-mghy.e.cdn.chinamobile.com",
		"cache.ott.bestlive.itv.cmvideo.cn=ip"
	],
	"lives": [],
	"rules": [],
	"doh": [
		{
			"name": "騰訊",
			"url": "https://doh.pub/dns-query"
		},
		{
			"name": "阿里",
			"url": "https://dns.alidns.com/dns-query"
		},
		{
			"name": "360",
			"url": "https://doh.360.cn/dns-query"
		}
	]
    }

=== 同步上游更新（本仓库为自维护 fork） ===

    # 只做一次的配置：把上游加为 upstream remote
    git remote add upstream git@github.com:q215613905/TVBoxOS.git

    # 日常同步
    git fetch upstream
    git merge upstream/main      # 自建定制全在 .github/ 里，与上游源码无重叠，正常零冲突
    git push origin main         # 触发 Build Custom

    注意：
    - 不要用 rebase，会把自建提交的 hash 全部重写，打乱 update.json 的增量基准
    - 合并前可先跑补丁 dry-run：把上游源码解到临时目录，执行
      bash .github/tvbox-patch/apply-patch.sh <raw> <cdn> <mirrors>，
      看灰度自检是否通过、锚点是否仍能命中（HomeActivity 的三处锚点是硬依赖）
    - .github/tvbox-patch/ 是运行时注入，不修改上游源码，所以通常不会产生冲突
