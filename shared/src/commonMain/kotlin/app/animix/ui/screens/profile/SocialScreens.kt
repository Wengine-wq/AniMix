package app.animix.ui.screens.profile

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.account.*
import app.animix.data.library.*
import app.animix.data.achievements.AchievementState
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.*
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import app.animix.core.AppJson

@Composable fun FriendsScreen(graph:AppGraph,navigator:Navigator,padding:PaddingValues) {
    val account by graph.account.state.collectAsState(); val social by graph.social.state.collectAsState()
    val scope=rememberCoroutineScope();var query by remember{mutableStateOf("")};var found by remember{mutableStateOf<List<SocialUser>>(emptyList())}
    var error by remember{mutableStateOf<String?>(null)};var busy by remember{mutableStateOf(false)};var visible by remember{mutableStateOf<Boolean?>(null)}
    fun action(block:suspend()->Unit){scope.launch{busy=true;try{block();error=null}catch(cancel:CancellationException){throw cancel}catch(_:Exception){error="Не удалось выполнить действие. Повторите при подключении."}finally{busy=false}}}
    LaunchedEffect(account.user?.id){if(account.user!=null)action{graph.social.refresh();visible=graph.social.privacy()}}
    LaunchedEffect(query){delay(400);try{found=graph.social.search(query)}catch(cancel:CancellationException){throw cancel}catch(_:Exception){error="Не удалось найти пользователей"}}
    ScreenColumn(padding){
        PageHeader("Друзья",{navigator.back()});Spacer(Modifier.height(20.dp))
        if(account.user==null){MessageState("Войдите в AniMix",message="Друзья и заявки доступны онлайн",icon=AniMixIcons.User);return@ScreenColumn}
        OutlinedTextField(query,{query=it},label={Text("Найти по имени")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp));(error?:social.error)?.let{Txt(it,AniMixTheme.type.caption)}
        visible?.let{value->Row{Checkbox(value,{next->action{graph.social.privacy(next);visible=next}},enabled=!busy);Txt("Показывать друзьям коллекцию и статистику",AniMixTheme.type.caption,modifier=Modifier.padding(top=12.dp))}}
        val rows=if(query.trim().length>=2)found else social.friends
        if(rows.isEmpty()) {Spacer(Modifier.height(20.dp));Txt(if(query.length>=2)"Никого не нашли" else "Пока нет друзей или заявок",AniMixTheme.type.caption)}
        for(section in listOf("incoming","outgoing","friends","none")){
            val group=rows.filter{row->(social.friends.find{it.id==row.id}?.status?:row.status)==section}
            if(group.isEmpty())continue
            Spacer(Modifier.height(24.dp));Txt(when(section){"incoming"->"Входящие заявки";"outgoing"->"Отправленные заявки";"friends"->"Друзья";else->"Результаты поиска"},AniMixTheme.type.heading)
            group.forEach{user->
                Panel(Modifier.padding(top=8.dp)) {Column(Modifier.padding(16.dp)){
                    NavRow(user.name,AniMixIcons.User,{navigator.push(Route.FriendProfile(user.id))})
                    Row {
                        if(section=="incoming"||section=="none")TextButton(enabled=!busy,onClick={action{graph.social.friendAction(user.id)}}){Text(if(section=="incoming")"Принять" else "Добавить")}
                        if(section!="none")TextButton(enabled=!busy,onClick={action{graph.social.friendAction(user.id,true)}}){Text(when(section){"incoming"->"Отклонить";"outgoing"->"Отменить заявку";else->"Удалить из друзей"})}
                    }
                }}
            }
        }
    }
}

@Composable fun FriendProfileScreen(graph:AppGraph,navigator:Navigator,id:String,padding:PaddingValues){
    val overview=rememberLoad(id){graph.social.overview(id)}
    LaunchedEffect(id){while(isActive){delay(15000);overview.refresh()}}
    ScreenColumn(padding){PageHeader("Профиль",{navigator.back()});Spacer(Modifier.height(20.dp))
        when(val state=overview.value){
            Load.Loading->LoadingIndicator()
            is Load.Failed->MessageState("Не удалось открыть профиль",message=state.error.userMessage(),icon=AniMixIcons.User,actionLabel="Повторить",onAction=overview::refresh)
            is Load.Ready->{
                val raw=state.value;val user=raw.getValue("user").jsonObject
                val lib=raw["library"]?.takeIf{it!=JsonNull}?.let{AppJson.decodeFromJsonElement<LibraryData>(it)}
                val awards=raw["achievements"]?.takeIf{it!=JsonNull}?.let{AppJson.decodeFromJsonElement<AchievementState>(it)}?:AchievementState()
                val profile=lib?.profile?:LocalProfile(name=user["display_name"]?.jsonPrimitive?.contentOrNull?:"AniMix",about=user["about"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    avatar=user["avatar_url"]?.jsonPrimitive?.contentOrNull?.let{ProfileMedia(url=it)},banner=user["banner_url"]?.jsonPrimitive?.contentOrNull?.let{ProfileMedia(url=it)})
                val badge=graph.achievements.all.firstOrNull{it.id==profile.badgeAchievementId}?.let{app.animix.data.achievements.AchievementProgress(it,1,awards.unlocked[it.id],awards.historical[it.id])}
                ProfileIdentity(profile,badge,level=lib?.level(awards.unlocked.size))
                Spacer(Modifier.height(12.dp));Txt(if(raw["online"]?.jsonPrimitive?.booleanOrNull==true)"В сети" else "Не в сети",AniMixTheme.type.caption)
                if(lib==null){Spacer(Modifier.height(24.dp));Txt("Коллекция и подробная статистика доступны друзьям с открытой коллекцией.",AniMixTheme.type.body)}
                else {
                    val stats=lib.stats();Spacer(Modifier.height(24.dp))
                    Row {ProfileStat(profileHours(stats.totalMinutes),"часов в AniMix",Modifier.weight(1f));ProfileStat("${stats.episodes}","серий",Modifier.weight(1f));ProfileStat("${stats.completed}","завершено",Modifier.weight(1f))}
                    Spacer(Modifier.height(24.dp));CollectionDistribution(lib,null)
                    Spacer(Modifier.height(24.dp));Txt("Активность",AniMixTheme.type.heading);ActivityChart(stats.lastDays)
                    Txt("Рекорд: ${stats.bestStreak} дней подряд · ${awards.unlocked.size+awards.historical.keys.count{it !in awards.unlocked}} достижений",AniMixTheme.type.caption)
                    if(stats.topGenres.isNotEmpty()){
                        Spacer(Modifier.height(24.dp));Txt("Любимые жанры",AniMixTheme.type.heading)
                        Spacer(Modifier.height(12.dp));GenreTaste(stats.topGenres)
                    }
                    Spacer(Modifier.height(24.dp));Txt("Коллекция",AniMixTheme.type.heading)
                    lib.entries.values.filter{it.status!=null||it.favorite}.sortedBy{it.anime.title}.forEach {entry->NavRow(entry.anime.title,AniMixIcons.Play,{navigator.push(Route.Detail(entry.anime))},trailing=entry.status?.label?:"Избранное")}
                }
            }
        }
    }
}

@Composable fun HorizonButton(graph:AppGraph){
    var open by remember{mutableStateOf(false)}
    TextButton(onClick={open=true}){Text("Horizon Link")}
    if(open)HorizonDialog(graph,{open=false})
}
@Composable fun HorizonDialog(graph:AppGraph,onDismiss:()->Unit){
    val social by graph.social.state.collectAsState();val scope=rememberCoroutineScope();var notice by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(Unit){try{graph.social.refresh()}catch(_:Exception){notice="Не удалось обновить устройства"}}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Продолжить на устройстве")},text={Column{
        val devices=social.devices.filter{it.id!=graph.social.deviceId&&it.online()}
        if(devices.isEmpty())Text("Откройте AniMix под тем же аккаунтом на другом устройстве.")
        devices.forEach{device->TextButton(onClick={scope.launch{try{graph.social.send(device.id);notice="Передано. Исходный плеер остановится после приёма."}catch(cancel:CancellationException){throw cancel}catch(_:Exception){notice="Передача не удалась. Проверьте устройство и повторите."}}}){Text(device.label)}}
        notice?.let{Text(it)}
    }},confirmButton={TextButton(onClick=onDismiss){Text("Готово")}})
}

@Composable fun HorizonIncoming(graph:AppGraph,navigator:Navigator){
    val social by graph.social.state.collectAsState()
    val item=social.incoming.firstOrNull()?:return
    var dismissed by remember(item.id){mutableStateOf(false)}
    if(dismissed)return
    AlertDialog(onDismissRequest={dismissed=true},title={Text("Продолжить здесь?")},text={Text("${item.playback.anime.title} · серия ${item.playback.episode}")},
        confirmButton={TextButton(onClick={
            val play=item.playback
            graph.watchFlow.pause()
            graph.library.saveEpisodePosition(play.anime,play.episode,play.positionMs)
            graph.settings.update{it.copy(preferredQuality=play.quality,dubbingByAnime=it.dubbingByAnime+(play.anime.key to play.dubbingKey))}
            graph.social.receive(item);dismissed=true
            navigator.push(Route.Watch(play.anime,play.episode,item.id))
        }){Text("Продолжить")}},dismissButton={TextButton(onClick={dismissed=true}){Text("Позже")}})
}
