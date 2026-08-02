cd F:\AiWork\TimeBus
git add -A
git reset -- 'src/main/resources/assets/timebus/textures/items/1.psb' 'src/main/resources/assets/timebus/textures/items/2.psd' 2>$null
echo '== staged =='
git status --short | Select-Object -First 15
git commit -m "v1.0.2: fix released-jar mixins and lang loading" -m "- MixinTileInscriber: replace @Redirect(ItemStack.getCount) with @ModifyVariable on all three getTask args (MC-class target names are srg in released jars; AE2 method names are stable). Batch parallel logic now works in released jars.
- MixinSlotRestrictedInput: dual-inject isItemValid + func_75214_a (require=0) - SlotRestrictedInput overrides MC Slot.isItemValid, runtime name is the srg func_75214_a; GUI click-in of parallel cards now works in released jars.
- MixinUpgradeInvFilter: allowInsert unaffected (AE2 method, stable).
- lang: rename en_US.lang/zh_CN.lang -> en_us.lang/zh_cn.lang (ResourceLocation lowercases paths, jar zip is case-sensitive -> item names showed raw keys in released jar; dev worked due to Windows case-insensitive fs).
- build.gradle: mixin annotation processor output redirected to build/apt-refmap so apt's empty refmap does not overwrite the hand-maintained one; jar manifest ships MixinConfigs.
- DEVELOPER.md: new 6.6 released-jar mixin pitfalls (10 items) + lang filename case (11)."
git -c http.proxy= -c https.proxy= push origin master 2>&1 | Select-Object -Last 3
echo '== status =='
git status -sb | Select-Object -First 1
