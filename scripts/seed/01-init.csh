mkdb ChronoMart
cd ChronoMart

mkcon Sellers      /id
mkcon Products     /sellerId
mkcon Inventory    /sellerId
mkcon Customers    /id
mkcon Reviews      /productId
mkcon Cart         /customerId
# vNext currently accepts two HPK levels consistently across cosmoshell and the
# Java/.NET SDK create paths. The target model adds /id as a third level once the
# emulator supports that shape consistently; changing PK paths requires recreation.
mkcon ProductsHpk  /sellerId,/categoryId
mkcon Orders       /customerId,/yearMonth
mkcon ChangeFeedLease /id
