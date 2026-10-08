create table singleKey (id identity not null, privateKey varbinary(32), compressed boolean not null, initialisationVector varbinary(32), encryptedBytes varbinary(255), keySalt varbinary(32), deriver integer, crypter integer);
alter table keystore add singlePublicKey varbinary(65);
alter table keystore add singleKey bigint;
alter table keystore alter column derivationPath drop not null;
alter table keystore add constraint keystore_singleKey_unique unique (singleKey);
alter table keystore add constraint keystore_singleKey foreign key (singleKey) references singleKey;
