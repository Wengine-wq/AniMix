class ShikimoriGenre {
  const ShikimoriGenre({
    required this.id,
    required this.name,
    required this.russian,
  });

  factory ShikimoriGenre.fromJson(Map<String, dynamic> json) => ShikimoriGenre(
    id: int.tryParse(json['id']?.toString() ?? '') ?? 0,
    name: json['name']?.toString().trim() ?? '',
    russian: json['russian']?.toString().trim() ?? '',
  );

  final int id;
  final String name;
  final String russian;

  String get label => russian.isNotEmpty ? russian : name;
}
