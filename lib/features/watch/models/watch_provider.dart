enum WatchProvider { yummyKodik, anilibria }

class WatchProviderInfo {
  final WatchProvider type;
  final String name;
  final String logoUrl; // можно потом подтянуть

  const WatchProviderInfo(this.type, this.name, this.logoUrl);
}

final List<WatchProviderInfo> availableProviders = [
  WatchProviderInfo(WatchProvider.yummyKodik, 'YummyAnime (Kodik)', 'https://yummyanime.tv/favicon.ico'),
  WatchProviderInfo(WatchProvider.anilibria, 'Anilibria (встроенный)', 'https://anilibria.tv/favicon.ico'),
];