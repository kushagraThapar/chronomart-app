mkdb ChronoMart
cd ChronoMart

mkcon Sellers      /id
mkcon Products     /sellerId
mkcon Inventory    /sellerId
mkcon Customers    /id
mkcon Reviews      /productId
mkcon Cart         /customerId
mkcon ProductsHpk  /sellerId,/categoryId
mkcon Orders       /customerId,/yearMonth
mkcon ChangeFeedLease /id
